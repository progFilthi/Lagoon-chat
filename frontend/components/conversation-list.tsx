"use client";

import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { api } from "@/lib/api/client";
import {
  avatarTint,
  chatPreview,
  chatTitle,
  counterpartOf,
  formatListTimestamp,
  formatUnread,
  initials,
} from "@/lib/format";
import type { ChatSummary } from "@/lib/api/types";
import { NewConversationDialog } from "./new-conversation";

type Filter = "all" | "unread" | "groups";

/**
 * A stable reference for the pre-load state. `data ?? []` inline would hand the
 * memoised filters a brand-new array on every render, so they would recompute on
 * every keystroke regardless of their dependencies.
 */
const NO_CHATS: ChatSummary[] = [];

/**
 * The conversation list.
 *
 * Ordering comes from the backend — `GET /api/chats` sorts by `lastMessageAt`
 * descending — and is deliberately not re-sorted here. Re-sorting on a client
 * clock would reorder rows against the server whenever a socket message arrives
 * and shifts the list underneath the reader.
 */
export function ConversationList({ selfId }: { selfId: string }) {
  const [filter, setFilter] = useState<Filter>("all");
  const [search, setSearch] = useState("");

  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: ["chats"],
    queryFn: api.chats,
  });

  const chats = data ?? NO_CHATS;

  const onlineCount = useMemo(
    () =>
      chats.filter((chat) =>
        chat.group
          ? chat.participants.some((p) => p.userId !== selfId && p.online)
          : counterpartOf(chat, selfId)?.online,
      ).length,
    [chats, selfId],
  );

  const unreadTotal = useMemo(
    () => chats.reduce((sum, chat) => sum + chat.unreadCount, 0),
    [chats],
  );

  const visible = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return chats.filter((chat) => {
      if (filter === "unread" && chat.unreadCount === 0) return false;
      if (filter === "groups" && !chat.group) return false;
      if (!needle) return true;
      // There is no server-side search endpoint, so this filters the loaded page.
      // That is a real limit: it cannot find a conversation that was never sent.
      return (
        chatTitle(chat, selfId).toLowerCase().includes(needle) ||
        (chat.lastMessagePreview ?? "").toLowerCase().includes(needle)
      );
    });
  }, [chats, filter, search, selfId]);

  return (
    <div className="flex w-[380px] shrink-0 flex-col border-r border-msg-hairline bg-msg-ground">
      <header className="flex shrink-0 items-center gap-3 px-5 pt-[18px] pb-3.5">
        <div className="min-w-0">
          <h1 className="text-msg-xl font-extrabold tracking-tight text-msg-ink">Chats</h1>
          <p className="text-msg-xs leading-4 font-medium text-[#5B6E69]">
            {onlineCount} active now
          </p>
        </div>
        <NewConversationDialog />
      </header>

      <div className="px-5 pb-3.5">
        <label className="flex h-[42px] shrink-0 items-center gap-2.5 rounded-full bg-msg-surface px-[14px]">
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            className="shrink-0 text-msg-ink-muted"
          >
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
          <input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search name or number"
            aria-label="Search conversations"
            className="min-w-0 flex-1 bg-transparent text-msg-sm text-msg-ink outline-none placeholder:text-msg-ink-muted"
          />
        </label>
      </div>

      <div className="mt-3 flex gap-2 px-5">
        <FilterChip active={filter === "all"} onClick={() => setFilter("all")}>
          All
        </FilterChip>
        <FilterChip active={filter === "unread"} onClick={() => setFilter("unread")}>
          Unread{unreadTotal > 0 ? ` ${unreadTotal}` : ""}
        </FilterChip>
        <FilterChip active={filter === "groups"} onClick={() => setFilter("groups")}>
          Groups
        </FilterChip>
      </div>

      {isPending ? (
        <ListState label="Loading conversations…" />
      ) : isError ? (
        <ListState
          label={error instanceof Error ? error.message : "Could not load conversations."}
          action={
            <button
              type="button"
              onClick={() => refetch()}
              className="cursor-pointer text-msg-sm font-bold text-msg-accent-text hover:underline"
            >
              Try again
            </button>
          }
        />
      ) : visible.length === 0 ? (
        <ListState
          label={
            search
              ? "Nothing matches that search."
              : filter === "unread"
                ? "No unread conversations."
                : filter === "groups"
                  ? "No group conversations yet."
                  : "No conversations yet. Start one to see it here."
          }
        />
      ) : (
        <ul className="mt-2 flex-1 overflow-y-auto">
          {visible.map((chat) => (
            <li key={chat.id}>
              <ChatRow chat={chat} selfId={selfId} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function FilterChip({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={[
        "shrink-0 cursor-pointer rounded-full px-[14px] py-[7px] text-msg-sm transition-colors",
        active
          ? "bg-msg-accent font-semibold text-msg-on-accent"
          : "bg-msg-surface text-msg-ink-muted hover:text-msg-ink",
      ].join(" ")}
    >
      {children}
    </button>
  );
}

function ChatRow({ chat, selfId }: { chat: ChatSummary; selfId: string }) {
  const counterpart = counterpartOf(chat, selfId);
  const title = chatTitle(chat, selfId);
  const online = chat.group
    ? chat.participants.some((p) => p.userId !== selfId && p.online)
    : Boolean(counterpart?.online);
  const preview = chatPreview(chat, selfId);

  return (
    <a
      href={`/chats/${chat.id}`}
      className="flex items-center gap-3 border-t border-msg-hairline px-5 py-3 transition-colors hover:bg-msg-surface"
    >
      <div className="relative w-11 shrink-0">
        <div
          className="flex size-11 items-center justify-center rounded-full text-msg-sm font-bold text-msg-ground"
          style={{ backgroundColor: avatarTint(chat.id) }}
        >
          {initials(title)}
        </div>
        {online ? (
          <span className="absolute right-0 bottom-0 size-3.5 rounded-full border-2 border-msg-ground bg-msg-accent" />
        ) : null}
      </div>

      <div className="min-w-0 flex-1">
        <p className="truncate text-msg-md leading-5 font-bold text-msg-ink">{title}</p>
        <p
          className={[
            "truncate text-msg-sm leading-[18px]",
            chat.unreadCount > 0 ? "text-msg-ink" : "text-msg-ink-muted",
          ].join(" ")}
        >
          {preview || "No messages yet"}
        </p>
      </div>

      {/* Fixed trailing lane, so the time and badge stack on the same axis in
          every row no matter how long the name or preview get. */}
      <div className="flex w-12 shrink-0 flex-col items-end gap-1.5">
        <span className="text-msg-2xs leading-[14px] font-bold text-msg-accent-text">
          {formatListTimestamp(chat.lastMessageAt)}
        </span>
        {chat.unreadCount > 0 ? (
          <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-msg-unread px-1.5 text-msg-2xs leading-[14px] font-bold text-msg-ink">
            {formatUnread(chat.unreadCount)}
          </span>
        ) : null}
      </div>
    </a>
  );
}

function ListState({ label, action }: { label: string; action?: React.ReactNode }) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-2 px-8 text-center">
      <p className="text-msg-sm leading-[18px] text-msg-ink-muted">{label}</p>
      {action}
    </div>
  );
}
