"use client";

import { Client, Versions, type IMessage, type StompSubscription } from "@stomp/stompjs";
import type {
  Message,
  PresenceEvent,
  ReadStateEvent,
  ReceiptPayload,
  SocketErrorEvent,
  TypingSignal,
} from "@/lib/api/types";

/**
 * The STOMP connection.
 *
 * **Authentication is the httpOnly cookie, not a header.** A STOMP CONNECT frame is built in
 * JavaScript and the WebSocket API cannot set request headers, so the only header-based route to
 * the token would mean JavaScript could read it — which defeats httpOnly. The backend reads the
 * `lagoon_session` cookie off the WebSocket *upgrade* request instead
 * (`JwtCookieHandshakeInterceptor`), and the browser attaches that by itself. So this client
 * deliberately sends no `connectHeaders`, and the token never enters this module.
 *
 * Destinations are dot separated because they map to RabbitMQ topic routing keys, which reject
 * `/`. Per-user topics are the only ones this subscribes to: the backend authorises SUBSCRIBE
 * against your own user id, so this cannot be used to listen to someone else's traffic.
 */

export type RealtimeStatus = "connecting" | "connected" | "reconnecting" | "offline";

export interface RealtimeHandlers {
  message?: (message: Message) => void;
  receipt?: (receipt: ReceiptPayload) => void;
  typing?: (typing: TypingSignal) => void;
  presence?: (presence: PresenceEvent) => void;
  readState?: (readState: ReadStateEvent) => void;
  socketError?: (error: SocketErrorEvent) => void;
  status?: (status: RealtimeStatus) => void;
}

/** The inbound application destinations, one per per-user channel. */
const CHANNELS = ["messages", "receipts", "typing", "presence", "read-state", "errors"] as const;
type Channel = (typeof CHANNELS)[number];

/** What we SEND to the backend. */
export const SEND_DESTINATION = "/app/chat.send";
export const RECEIPT_DESTINATION = "/app/chat.receipt";
export const TYPING_DESTINATION = "/app/chat.typing";

/** `/topic/user.{yourId}.{channel}` — your own traffic only. */
export function userTopic(userId: string, channel: Channel | string): string {
  return `/topic/user.${userId}.${channel}`;
}

function websocketUrl(): string {
  const explicit = process.env.NEXT_PUBLIC_BACKEND_WS_URL;
  if (explicit) return explicit;

  // Derived rather than hardcoded so the app works from any host the dev server is
  // reached on. Cookies ignore the port, so the session cookie still rides along.
  const backend = process.env.NEXT_PUBLIC_BACKEND_URL;
  if (backend) return `${backend.replace(/^http/, "ws").replace(/\/$/, "")}/ws`;

  const host = typeof window === "undefined" ? "localhost" : window.location.hostname || "localhost";
  return `ws://${host}:8080/ws`;
}

let receiptCounter = 0;

function nextReceiptId(): string {
  receiptCounter += 1;
  return `r-${receiptCounter}-${Math.random().toString(36).slice(2, 8)}`;
}

class RealtimeConnection {
  private client: Client | null = null;
  private subscriptions: StompSubscription[] = [];
  private handlers: RealtimeHandlers = {};
  private selfId: string | null = null;
  private status: RealtimeStatus = "offline";

  setHandlers(handlers: RealtimeHandlers): void {
    this.handlers = handlers;
  }

  getStatus(): RealtimeStatus {
    return this.status;
  }

  get isConnected(): boolean {
    return this.client?.connected === true;
  }

  /**
   * Opens the socket, or does nothing if it is already open for this user.
   *
   * Reconnection is the client's job, not the caller's: the STOMP library retries with a fixed
   * delay and re-runs `onConnect`, which is where the subscriptions are re-established. React
   * strict mode double-invokes effects, so this has to be idempotent.
   */
  connect(selfId: string): void {
    if (this.client && this.selfId === selfId) return;
    if (this.client) this.teardown();

    this.selfId = selfId;
    this.setStatus("connecting");

    const client = new Client({
      brokerURL: websocketUrl(),
      // STOMP 1.2 only. The backend relays to RabbitMQ's stomp plugin, which speaks 1.2.
      stompVersions: new Versions(["1.2"]),
      // Fixed rather than exponential: a chat client should come back quickly after a laptop
      // lid closes, and the backend holds no per-session state we would be racing.
      reconnectDelay: 4000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      // Fail fast instead of hanging a tab on a backend that is not there.
      connectionTimeout: 10000,
    });

    client.onConnect = () => {
      this.setStatus("connected");
      this.subscribeAll();
    };

    client.onStompError = (frame) => {
      // A STOMP ERROR during connect means the token was missing, malformed or expired, and
      // the backend says nothing else. Recovery is re-authenticating, so surface it rather
      // than reconnecting forever against a credential that will never work.
      const reason = frame.headers.message ?? "";
      if (/ERROR/i.test(reason) || frame.headers["message"]?.includes("ERROR")) {
        this.setStatus("offline");
      }
    };

    client.onWebSocketError = () => {
      // The library retries on its own schedule; this only corrects the badge in the UI.
      if (this.status !== "connected") this.setStatus("reconnecting");
    };

    client.onDisconnect = () => {
      this.subscriptions = [];
      this.setStatus("offline");
    };

    this.client = client;
    client.activate();
  }

  private subscribeAll(): void {
    const userId = this.selfId;
    if (!userId) return;

    for (const channel of CHANNELS) {
      this.subscriptions.push(
        this.client!.subscribe(userTopic(userId, channel), (frame) => {
          this.dispatch(channel, frame);
        }),
      );
    }
  }

  private dispatch(channel: Channel, frame: IMessage): void {
    if (!frame.body) return;

    let parsed: unknown;
    try {
      parsed = JSON.parse(frame.body);
    } catch {
      // A frame we cannot parse is not actionable, and throwing here would tear down the
      // subscription for a channel that is otherwise healthy.
      return;
    }

    switch (channel) {
      case "messages":
        this.handlers.message?.(parsed as Message);
        break;
      case "receipts":
        this.handlers.receipt?.(parsed as ReceiptPayload);
        break;
      case "typing":
        this.handlers.typing?.(parsed as TypingSignal);
        break;
      case "presence":
        this.handlers.presence?.(parsed as PresenceEvent);
        break;
      case "read-state":
        this.handlers.readState?.(parsed as ReadStateEvent);
        break;
      case "errors":
        this.handlers.socketError?.(parsed as SocketErrorEvent);
        break;
    }
  }

  /**
   * Publishes a frame and returns the `receipt` id used, or null when the socket is down.
   *
   * The receipt is echoed back on `/topic/user.{id}.errors` when the backend rejects the frame,
   * which is the only way to attribute a failure to one specific in-flight send rather than to
   * the connection as a whole. Callers must treat null as "not sent" — the message stays
   * pending and the caller decides whether to retry, which is safe because `clientMessageId`
   * makes a replay idempotent.
   */
  publish(destination: string, body: unknown): string | null {
    if (!this.client?.connected) return null;

    const receipt = nextReceiptId();
    this.client.publish({
      destination,
      headers: { receipt },
      body: JSON.stringify(body),
    });
    return receipt;
  }

  disconnect(): void {
    this.teardown();
  }

  private teardown(): void {
    for (const subscription of this.subscriptions) {
      try {
        subscription.unsubscribe();
      } catch {
        // Already gone with the socket; nothing to undo.
      }
    }
    this.subscriptions = [];

    const client = this.client;
    this.client = null;
    this.selfId = null;
    void client?.deactivate();
    this.setStatus("offline");
  }

  private setStatus(status: RealtimeStatus): void {
    if (this.status === status) return;
    this.status = status;
    this.handlers.status?.(status);
  }
}

/** One connection per tab. The layout mounts the provider once above every route. */
export const realtime = new RealtimeConnection();