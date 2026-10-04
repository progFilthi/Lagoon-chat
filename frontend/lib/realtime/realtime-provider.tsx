"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useQueryClient, type InfiniteData } from "@tanstack/react-query";
import {
  RECEIPT_DESTINATION,
  SEND_DESTINATION,
  TYPING_DESTINATION,
  realtime,
  type RealtimeStatus,
} from "./socket";
import { api } from "@/lib/api/client";
import { chatsKey, messageKeys } from "@/lib/chat/keys";
import {
  advanceTick,
  appendNewest,
  chronological,
  findByClientMessageId,
  insertNewest,
  patchByClientMessageId,
  removeByClientMessageId,
  type Page,
  type Pages,
} from "@/lib/chat/cache";
import type { DisplayMessage } from "@/lib/chat/types";
import type {
  ChatSummary,
  MessageStatus,
  MessageType,
  ReceiptPayload,
  SendMessagePayload,
  TypingSignal,
} from "@/lib/api/types";

/**
 * Owns the socket for the whole signed-in app and folds its events into the query cache.
 *
 * The split of responsibility: the socket is the source of truth for anything live and the
 * cache is the source of truth for anything historical. An incoming message therefore writes
 * straight into `['messages', chatId]` instead of triggering a refetch, because the broadcast
 * already carries a complete `MessageResponse` — there is nothing to go and ask for. The chat
 * list is patched for the same reason and then reconciled by a refetch, because
 * `lastMessagePreview` is generated server-side and only the server can produce it correctly.
 */

interface PendingSend {
  chatId: string;
  clientMessageId: string;
}

export interface SendInput {
  chatId: string;
  content: string;
  type: MessageType;
  mediaKey?: string | null;
  replyToId?: string | null;
}

export interface RealtimeContextValue {
  status: RealtimeStatus;
  selfId: string;
  /** The latest typing signal for a chat, or null. */
  typingIn: (chatId: string) => TypingSignal | null;
  sendMessage: (input: SendInput) => string;
  sendReceipt: (messageId: string, status: Extract<MessageStatus, "DELIVERED" | "READ">) => void;
  sendTyping: (messageId: string) => void;
  markChatRead: (chatId: string) => void;
  /** The chat on screen, so only that one auto-acks receipts and clears unread. */
  setActiveChat: (chatId: string | null) => void;
}

const RealtimeContext = createContext<RealtimeContextValue | null>(null);

/** How long a typing indicator survives without a refresh. See the typing handler. */
const TYPING_TTL_MS = 6000;

export function RealtimeProvider({ selfId, children }: { selfId: string; children: ReactNode }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<RealtimeStatus>("offline");

  /** receipt id -> the send it identifies, so one failure fails one bubble. */
  const pendingSends = useRef(new Map<string, PendingSend>());

  const [typing, setTyping] = useState<Record<string, TypingSignal>>({});
  const typingTimers = useRef(new Map<string, ReturnType<typeof setTimeout>>());

  /** Read by socket handlers, written by the chat route. A ref, not state: handlers
   *  are registered once and must see the current value without re-subscribing. */
  const activeChatId = useRef<string | null>(null);

  /* ------------------------------------------------------------ cache plumbing --- */

  /**
   * Patches one chat's cached history in place.
   *
   * Note the shape: `useInfiniteQuery` does not store a bare `MessagePage[]` in the cache, it
   * stores `InfiniteData` — `{ pages, pageParams }`. The page helpers work on the page list, so
   * the wrapper is unwrapped here and re-wrapped on the way out. Treating the cached value as
   * the array itself is the bug that produced `pages is not iterable`: the object is truthy, so
   * a naive `if (data)` guard passes and the array operation fails at runtime.
   */
  const patchPages = useCallback(
    (chatId: string, mutate: (pages: Pages) => Pages) => {
      queryClient.setQueryData<InfiniteData<Page>>(messageKeys.list(chatId), (data) => {
        if (!data?.pages) return data;
        return { ...data, pages: mutate(data.pages) };
      });
    },
    [queryClient],
  );

  const patchChatList = useCallback(
    (mutate: (chat: ChatSummary) => ChatSummary) => {
      queryClient.setQueryData<ChatSummary[]>(chatsKey, (chats) => {
        if (!chats) return chats;
        let changed = false;
        const next = chats.map((chat) => {
          const updated = mutate(chat);
          if (updated !== chat) changed = true;
          return updated;
        });
        return changed ? next : chats;
      });
    },
    [queryClient],
  );

  /* ------------------------------------------------------------- socket wiring --- */

  useEffect(() => {
    realtime.setHandlers({
      status: setStatus,

      message: (message) => {
        const isOwn = message.senderId === selfId;
        const open = activeChatId.current === message.chatId;

        // Reconcile by clientMessageId: the echo carries the real id and the real content,
        // so the optimistic bubble is simply replaced and nothing is refetched.
        if (isOwn) {
          patchPages(message.chatId, (pages) => removeByClientMessageId(pages, message.clientMessageId));
        }

        // Only write into history that is actually loaded. If the chat was never opened there
        // is nothing to patch, and the next history fetch will include this message anyway.
        if (queryClient.getQueryData<InfiniteData<Page>>(messageKeys.list(message.chatId))?.pages) {
          patchPages(message.chatId, (pages) => insertNewest(pages, message));
        }

        // Arriving in a chat you are looking at means it is read by definition.
        if (!isOwn && open) sendReceiptFor(message.id);

        patchChatList((chat) => {
          if (chat.id !== message.chatId) return chat;
          return {
            ...chat,
            lastMessageAt: message.createdAt,
            lastMessageSenderId: message.senderId,
            unreadCount: isOwn || open ? chat.unreadCount : chat.unreadCount + 1,
          };
        });

        // Deliberately no re-sort of the list here — it is ordered by the server on
        // `lastMessageAt desc` and re-sorting on a client clock moves rows under the reader.
        // The refetch restores the authoritative order and the server-built preview.
        void queryClient.invalidateQueries({ queryKey: chatsKey });
      },

      receipt: (receipt: ReceiptPayload) => {
        // A receipt frame names a message but not a chat, so the chat is recovered from the
        // loaded caches. Receipts about a chat you have not opened simply do not apply.
        const chatId = chatIdForLoadedMessage(queryClient, receipt.messageId);
        if (chatId) {
          patchPages(chatId, (pages) => advanceTick(pages, receipt.messageId, receipt.status));
        }
        // No invalidate: the counts live on history, and a live status is all a tick needs.
      },

      typing: (signal) => {
        if (signal.userId === selfId) return;
        setTyping((current) => ({ ...current, [signal.chatId]: signal }));

        // This contract has no "stopped typing" frame, so the indicator is given a lifetime
        // instead. Without one it would stay up forever after the last keystroke.
        const existing = typingTimers.current.get(signal.chatId);
        if (existing) clearTimeout(existing);
        typingTimers.current.set(
          signal.chatId,
          setTimeout(() => {
            typingTimers.current.delete(signal.chatId);
            setTyping((current) => {
              if (current[signal.chatId]?.at !== signal.at) return current;
              const next = { ...current };
              delete next[signal.chatId];
              return next;
            });
          }, TYPING_TTL_MS),
        );
      },

      presence: (presence) => {
        patchChatList((chat) =>
          chat.participants.some((p) => p.userId === presence.userId)
            ? {
                ...chat,
                participants: chat.participants.map((participant) =>
                  participant.userId === presence.userId
                    ? { ...participant, online: presence.online }
                    : participant,
                ),
              }
            : chat,
        );
      },

      readState: (event) => {
        // Unread is a per-user projection, so this arrives on your own topic and is the signal
        // to zero that chat's counter — including in your other tabs.
        patchChatList((chat) => (chat.id === event.chatId ? { ...chat, unreadCount: 0 } : chat));
      },

      socketError: (error) => {
        if (!error.receipt) return;
        const pending = pendingSends.current.get(error.receipt);
        if (!pending) return;
        pendingSends.current.delete(error.receipt);

        // The socket deliberately stays open on a frame-level error; only this bubble fails.
        patchPages(pending.chatId, (pages) =>
          patchByClientMessageId(pages, pending.clientMessageId, (message) => ({
            ...message,
            pending: false,
            failed: true,
            failureReason: error.message,
          })),
        );
      },
    });

    realtime.connect(selfId);

    return () => {
      realtime.setHandlers({});
      realtime.disconnect();
    };
  }, [queryClient, selfId, patchPages, patchChatList]);

  /* ------------------------------------------------------------------ outgoing --- */

  function sendReceiptFor(messageId: string, status: Extract<MessageStatus, "DELIVERED" | "READ"> = "DELIVERED") {
    realtime.publish(RECEIPT_DESTINATION, {
      messageId,
      status,
      // Both are ignored by the server on send but required by ReceiptEventPayload.
      chatMemberIds: [],
      occurredAt: null,
    } satisfies ReceiptPayload);
  }

  const sendMessage = useCallback(
    ({ chatId, content, type, mediaKey, replyToId }: SendInput): string => {
      // Generated once and reused across retries, so a resend after reconnect is idempotent
      // rather than a duplicate.
      const clientMessageId = crypto.randomUUID();

      const optimistic: DisplayMessage = {
        id: `pending-${clientMessageId}`,
        chatId,
        senderId: selfId,
        clientMessageId,
        content,
        type,
        replyToId: replyToId ?? null,
        createdAt: new Date().toISOString(),
        receipts: { total: 0, delivered: 0, read: 0 },
        pending: true,
        tick: "SENT",
      };

      const payload: SendMessagePayload = {
        chatId,
        clientMessageId,
        content,
        type,
        replyToId: replyToId ?? null,
        mediaKey: mediaKey ?? null,
      };

      patchPages(chatId, (pages) => appendNewest(pages, optimistic));

      const receipt = realtime.publish(SEND_DESTINATION, payload);
      if (receipt) {
        pendingSends.current.set(receipt, { chatId, clientMessageId });
      } else {
        // Never left the device. Marking it failed is the honest outcome — a bubble that
        // spins forever would be worse than one that says it did not send.
        patchPages(chatId, (pages) =>
          patchByClientMessageId(pages, clientMessageId, (message) => ({
            ...message,
            pending: false,
            failed: true,
            failureReason: "Not connected.",
          })),
        );
      }

      return clientMessageId;
    },
    [selfId, patchPages],
  );

  const sendReceipt = useCallback(
    (messageId: string, receiptStatus: Extract<MessageStatus, "DELIVERED" | "READ">) => {
      sendReceiptFor(messageId, receiptStatus);
    },
    [],
  );

  /**
   * The server resolves the chat from `messageId`, so this needs a message already in the chat.
   * A brand new empty chat has nothing to announce, which is why this takes an id rather than
   * a chat id.
   */
  const sendTyping = useCallback((messageId: string) => {
    realtime.publish(TYPING_DESTINATION, {
      messageId,
      status: "SENT",
      chatMemberIds: [],
      occurredAt: new Date().toISOString(),
    } satisfies ReceiptPayload);
  }, []);

  const markChatRead = useCallback(
    (chatId: string) => {
      patchChatList((chat) => (chat.id === chatId ? { ...chat, unreadCount: 0 } : chat));
      void api.markRead(chatId).catch(() => {
        // Zeroed optimistically on purpose: the badge reflects a read the user has already
        // acted on. If the POST fails the next open of the chat retries it.
      });
    },
    [patchChatList],
  );

  const setActiveChat = useCallback((chatId: string | null) => {
    activeChatId.current = chatId;
  }, []);

  const typingIn = useCallback((chatId: string) => typing[chatId] ?? null, [typing]);

  /* ------------------------------------------------------------------- cleanup --- */

  useEffect(() => {
    const timers = typingTimers.current;
    const sends = pendingSends.current;
    return () => {
      for (const timer of timers.values()) clearTimeout(timer);
      timers.clear();
      sends.clear();
    };
  }, []);

  const value = useMemo<RealtimeContextValue>(
    () => ({
      status,
      selfId,
      typingIn,
      sendMessage,
      sendReceipt,
      sendTyping,
      markChatRead,
      setActiveChat,
    }),
    [status, selfId, typingIn, sendMessage, sendReceipt, sendTyping, markChatRead, setActiveChat],
  );

  return <RealtimeContext.Provider value={value}>{children}</RealtimeContext.Provider>;
}

export function useRealtime(): RealtimeContextValue {
  const context = useContext(RealtimeContext);
  if (!context) throw new Error("useRealtime must be used inside <RealtimeProvider>");
  return context;
}

/* --------------------------------------------------------------------- helpers --- */

/**
 * Recovers which loaded chat a message belongs to.
 *
 * A receipt frame carries only a message id, but the tick has to be patched into a specific
 * chat's cache. Searching the loaded caches is cheap — a handful of chats are ever open — and
 * it avoids inventing a chat id that would silently write to nothing.
 */
function chatIdForLoadedMessage(
  queryClient: ReturnType<typeof useQueryClient>,
  messageId: string,
): string | null {
  for (const query of queryClient.getQueryCache().findAll({ queryKey: messageKeys.allChats() })) {
    const chatId = query.queryKey[1];
    if (typeof chatId !== "string") continue;
    const pages = (query.state.data as InfiniteData<Page> | undefined)?.pages;
    if (!pages) continue;
    if (pages.some((page) => page.content.some((message) => message.id === messageId))) {
      return chatId;
    }
  }
  return null;
}

export { chronological, findByClientMessageId };