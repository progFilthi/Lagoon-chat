"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useEffect, useLayoutEffect, useMemo, useRef } from "react";
import { api } from "@/lib/api/client";
import { chatKey, messageKeys } from "@/lib/chat/keys";
import { chronological } from "@/lib/chat/cache";
import { useRealtime } from "@/lib/realtime/realtime-provider";
import { avatarTint, chatTitle, counterpartOf, initials, formatLastSeen } from "@/lib/format";
import { MessageBubble } from "./message-bubble";
import { MessageComposer } from "./message-composer";
import type { MessagePage } from "@/lib/api/types";
import type { DisplayMessage } from "@/lib/chat/types";

const PAGE_SIZE = 30;

/**
 * The conversation pane.
 *
 * History is loaded with `useInfiniteQuery` walking *backwards* — the backend returns each page
 * newest-first with an opaque cursor, and `getNextPageParam` feeds that cursor back unchanged.
 * The rendered order is assembled by `chronological`, which reverses both the page order and
 * each page's contents; that reversal lives in one place on purpose, because getting it subtly
 * wrong renders the thread upside down rather than erroring.
 *
 * Newest messages are appended by the socket straight into this same cache, so a message
 * arriving never triggers a refetch and never fights the pagination cursor.
 */
export function ChatView({ chatId, selfId }: { chatId: string; selfId: string }) {
  const { typingIn, markChatRead, setActiveChat, sendReceipt, status } = useRealtime();

  const chat = useQuery({
    queryKey: chatKey(chatId),
    queryFn: () => api.chat(chatId),
  });

  const history = useInfiniteQuery({
    queryKey: messageKeys.list(chatId),
    queryFn: ({ pageParam }) => api.messages(chatId, pageParam as string | null, PAGE_SIZE),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage: MessagePage) => (lastPage.hasMore ? lastPage.nextCursor : undefined),
  });

  const messages = useMemo(() => chronological(history.data?.pages), [history.data?.pages]);

  const counterpart = chat.data ? counterpartOf(chat.data, selfId) : undefined;
  const title = chat.data ? chatTitle(chat.data, selfId) : "";

  // The chat summary carries presence but not `lastSeen`, so the profile is fetched for it
  // separately — and only for a 1:1 chat, since a group has no single "last seen".
  const counterpartProfile = useQuery({
    queryKey: ["user", counterpart?.userId],
    queryFn: () => api.user(counterpart!.userId),
    enabled: Boolean(counterpart) && !chat.data?.group,
    staleTime: 60_000,
  });

  /* --------------------------------------------------------------- read state --- */

  // Registering the open chat is what lets the socket know to auto-ack receipts and stop
  // counting unread for it. Done in an effect keyed on the id so a chat switch is observed.
  useEffect(() => {
    setActiveChat(chatId);
    return () => setActiveChat(null);
  }, [chatId, setActiveChat]);

  const unreadIncoming = useMemo(
    () => messages.filter((message) => message.senderId !== selfId && !message.pending).length,
    [messages, selfId],
  );

  // Only the last stretch of an unread thread gets read receipts — sending one per historical
  // message on open would be a burst of frames for no benefit.
  useEffect(() => {
    if (history.isPending) return;
    if (unreadIncoming === 0) return;

    markChatRead(chatId);
    const lastId = messages[messages.length - 1]?.id;
    if (lastId) sendReceipt(lastId, "READ");
  }, [history.isPending, unreadIncoming, chatId, messages, markChatRead, sendReceipt]);

  // A tab left open in the background should not claim to have read what arrived there.
  useEffect(() => {
    function onFocus() {
      if (document.visibilityState === "visible") markChatRead(chatId);
    }
    document.addEventListener("visibilitychange", onFocus);
    window.addEventListener("focus", onFocus);
    return () => {
      document.removeEventListener("visibilitychange", onFocus);
      window.removeEventListener("focus", onFocus);
    };
  }, [chatId, markChatRead]);

  /* ----------------------------------------------------------------- scrolling --- */

  const scrollRef = useRef<HTMLDivElement>(null);
  const atBottom = useRef(true);
  /** Tracks whether the newest message arrived while the reader was scrolled away. */
  const newestId = messages[messages.length - 1]?.id;

  function handleScroll() {
    const node = scrollRef.current;
    if (!node) return;
    atBottom.current = node.scrollHeight - node.scrollTop - node.clientHeight < 80;
  }

  // Layout effect, not effect: this has to run before the browser paints or the thread visibly
  // jumps once on every load.
  useLayoutEffect(() => {
    const node = scrollRef.current;
    if (node && atBottom.current) node.scrollTop = node.scrollHeight;
  }, [newestId, messages.length]);

  // Scroll to the newest when a fresh message arrives, but only if the reader was already at
  // the bottom — yanking someone away from history they are reading is the classic chat bug.
  useLayoutEffect(() => {
    const node = scrollRef.current;
    if (node && newestId && atBottom.current) node.scrollTop = node.scrollHeight;
  }, [newestId]);

  /**
   * Interleaves day separators into the thread and works out where each bubble keeps its tail.
   *
   * Both are derived here rather than in the bubble because both need to look across
   * neighbouring messages — a separator depends on the previous message's day, and a tail is
   * suppressed only when the *next* message is from the same sender.
   */
  const rendered = useMemo(() => {
    const nodes: React.ReactNode[] = [];
    let previousDay: string | null = null;

    messages.forEach((message, index) => {
      const day = dayKey(message.createdAt);
      if (day !== previousDay) {
        nodes.push(<DaySeparator key={`day-${day}-${index}`} at={message.createdAt} />);
        previousDay = day;
      }

      const next = messages[index + 1];
      nodes.push(
        <MessageBubble
          key={message.clientMessageId || message.id}
          message={message}
          own={message.senderId === selfId}
          showTail={!next || next.senderId !== message.senderId}
        />,
      );
    });

    return nodes;
  }, [messages, selfId]);

  const typing = typingIn(chatId);
  const online = Boolean(counterpart?.online);

  return (
    // The fade is drawn *over* the surface colour, not instead of it, so the top of the thread
    // dissolves into the header hairline instead of ending on a hard edge.
    <section className="flex min-w-0 flex-1 flex-col bg-msg-surface bg-[linear-gradient(to_bottom,var(--msg-thread-top),transparent_88px)]">
      <header className="flex shrink-0 items-center gap-3 border-b border-msg-hairline bg-msg-ground px-5 py-3.5">
        <div className="relative size-[42px] shrink-0">
          <div
            className="flex size-[42px] items-center justify-center rounded-full text-msg-sm font-bold text-msg-ground"
            style={{ backgroundColor: avatarTint(chatId) }}
          >
            {initials(title)}
          </div>
          {online ? (
            <span className="absolute right-0 bottom-0 size-[13px] rounded-full border-2 border-msg-ground bg-msg-accent" />
          ) : null}
        </div>

        <div className="min-w-0 flex-1">
          <h2 className="truncate text-msg-md leading-5 font-bold text-msg-ink">{title}</h2>
          <p className="truncate text-msg-xs leading-4 font-medium text-msg-accent-text">
            {typing ? "typing…" : online ? "online" : formatLastSeen(counterpartProfile.data?.lastSeen ?? null)}
          </p>
        </div>

        <ConnectionBadge status={status} />
      </header>

      <div ref={scrollRef} onScroll={handleScroll} className="flex-1 overflow-y-auto">
        {history.isPending ? (
          <p className="p-6 text-center text-msg-sm text-msg-ink-muted">Loading messages…</p>
        ) : history.isError ? (
          <div className="p-6 text-center">
            <p className="text-msg-sm text-msg-live-text">
              {history.error instanceof Error ? history.error.message : "Could not load messages."}
            </p>
            <button
              type="button"
              onClick={() => history.refetch()}
              className="mt-2 cursor-pointer text-msg-sm font-bold text-msg-accent-text hover:underline"
            >
              Try again
            </button>
          </div>
        ) : messages.length === 0 ? (
          <p className="p-6 text-center text-msg-sm text-msg-ink-muted">
            No messages yet. Say something.
          </p>
        ) : (
          // Bottom-aligned and full-bleed: the artboard pins the thread to the bottom edge and
          // lets bubbles run to the pane's own padding, so there is no inner max-width column.
          <div className="flex min-h-full flex-col justify-end gap-1.5 px-7 py-6">
            {history.hasNextPage ? (
              <div className="pb-1 text-center">
                <button
                  type="button"
                  onClick={() => history.fetchNextPage()}
                  disabled={history.isFetchingNextPage}
                  className="cursor-pointer rounded-full bg-msg-ground px-4 py-1.5 text-msg-sm text-msg-ink-muted hover:text-msg-ink disabled:opacity-60"
                >
                  {history.isFetchingNextPage ? "Loading…" : "Load earlier messages"}
                </button>
              </div>
            ) : null}

            {rendered}

            {typing ? <TypingPill /> : null}
          </div>
        )}
      </div>

      {/* Keyed on the chat so switching conversations remounts the composer, which discards a
          half-typed draft and any upload error. Resetting that in an effect instead would
          render the stale draft for a frame first. */}
      <MessageComposer
        key={chatId}
        chatId={chatId}
        latestMessageId={messages[messages.length - 1]?.id ?? null}
      />
    </section>
  );
}

function ConnectionBadge({ status }: { status: ReturnType<typeof useRealtime>["status"] }) {
  if (status === "connected") return null;
  return (
    <span
      role="status"
      className="shrink-0 rounded-full bg-msg-surface px-3 py-1 text-msg-2xs leading-[14px] font-semibold text-msg-ink-muted"
    >
      {status === "connecting" ? "Connecting…" : status === "reconnecting" ? "Reconnecting…" : "Offline"}
    </span>
  );
}

export type { DisplayMessage };

/** Local calendar day. Deliberately not UTC — "Today" must match the reader's clock. */
function dayKey(iso: string): string {
  const date = new Date(iso);
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}

/**
 * The centred day pill. Uppercase 11px with wide tracking and a muted surface fill, matching
 * the artboard — it has to stay legible at that size without competing with the bubbles, so the
 * tracking does the work the size cannot.
 */
function DaySeparator({ at }: { at: string }) {
  const label = formatDayLabel(at);
  if (!label) return null;
  return (
    <div className="flex justify-center py-1.5">
      <span className="rounded-full bg-msg-surface-muted px-3 py-[5px] text-msg-2xs leading-[14px] font-bold tracking-[0.1em] text-[#5B6E69] uppercase">
        {label}
      </span>
    </div>
  );
}

/**
 * The typing indicator, as a small incoming bubble with three staggered dots rather than a line
 * of text — it has to read as "someone is about to send something" in the thread's own visual
 * language. The opacity ramp (35 / 65 / 100) is the design's animation cue.
 */
function TypingPill() {
  return (
    <div className="flex w-full items-end justify-start">
      <div className="flex items-center gap-1.5 rounded-msg-md rounded-bl-msg-xs border border-msg-hairline bg-msg-ground p-3.5">
        {[35, 65, 100].map((opacity) => (
          <span
            key={opacity}
            className="size-[7px] rounded-full bg-msg-accent motion-safe:animate-pulse"
            style={{ opacity: opacity / 100, animationDelay: `${opacity === 35 ? 0 : opacity === 65 ? 150 : 300}ms` }}
          />
        ))}
      </div>
    </div>
  );
}

/** "Today" / "Yesterday" / an absolute date once it is neither. */
function formatDayLabel(iso: string): string {
  const date = new Date(iso);
  const now = new Date();
  const startOf = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const days = Math.round((startOf(now) - startOf(date)) / 86_400_000);

  if (days === 0) return "Today";
  if (days === 1) return "Yesterday";
  if (days < 7) return date.toLocaleDateString(undefined, { weekday: "long" });

  return date.toLocaleDateString(undefined, {
    day: "numeric",
    month: "long",
    year: date.getFullYear() === now.getFullYear() ? undefined : "numeric",
  });
}
