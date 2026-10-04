import type { ChatSummary } from "@/lib/api/types";

/**
 * Presentation helpers for the conversation list.
 *
 * The preview string is generated *server-side* by `ChatService.previewOf`, which
 * labels an attachment by kind ("Photo", "Video", "Audio") and appends the caption
 * when there is one. So there is nothing to reconstruct here — no "Sent a photo",
 * no "Voice message · 0:24". What the client does add is the sender prefix for
 * groups, because `lastMessagePreview` deliberately carries only the content.
 */

/** "09:47" today, "Yesterday", "Tue", then "12 Jun". */
export function formatListTimestamp(iso: string | null, now = new Date()): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";

  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const time = date.getTime();

  if (time >= startOfToday) {
    return date.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit", hour12: false });
  }

  const daysAgo = Math.floor((startOfToday - new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()) / 86_400_000);
  if (daysAgo === 1) return "Yesterday";
  if (daysAgo < 7) return date.toLocaleDateString(undefined, { weekday: "short" });
  return date.toLocaleDateString(undefined, { day: "numeric", month: "short" });
}

/** "14:32" — the timestamp that sits inline at the end of a bubble. */
export function formatBubbleTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit", hour12: false });
}

/** "last seen 12m ago" — only shown when actually offline. */
export function formatLastSeen(iso: string | null, now = new Date()): string {
  if (!iso) return "offline";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "offline";

  const seconds = Math.max(0, Math.round((now.getTime() - then) / 1000));
  if (seconds < 60) return "last seen just now";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `last seen ${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `last seen ${hours}h ago`;
  return `last seen ${Math.round(hours / 24)}d ago`;
}

export function initials(name: string): string {
  const parts = name.trim().split(/[\s._-]+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}

/** The other participant in a 1:1 chat. */
export function counterpartOf(chat: ChatSummary, selfId: string) {
  return chat.participants.find((participant) => participant.userId !== selfId) ?? null;
}

/** Group title: the name, or the member count when there is no name. */
export function chatTitle(chat: ChatSummary, selfId: string): string {
  if (chat.group) return chat.groupName ?? "Group";
  return counterpartOf(chat, selfId)?.username ?? "Unknown";
}

/**
 * The preview line.
 *
 * A group prefixes the sender's first name, built from `lastMessageSenderId`
 * against the participants the response already carries. A 1:1 chat shows the
 * content bare, because "Amara: …" above a row already labelled Amara is noise.
 */
export function chatPreview(chat: ChatSummary, selfId: string): string {
  const preview = chat.lastMessagePreview ?? "";
  if (!chat.group || !chat.lastMessageSenderId) return preview;
  if (chat.lastMessageSenderId === selfId) return preview;

  const sender = chat.participants.find((p) => p.userId === chat.lastMessageSenderId);
  const prefix = sender ? `${sender.username.split(/[\s._-]+/)[0]}: ` : "";
  return prefix + preview;
}

/**
 * Avatar background. Cycles the accent family so a list of names stays
 * distinguishable without ever inventing a photo — the DS states a photo is never
 * in a list row.
 */
const AVATAR_TINTS = [
  "var(--msg-accent)",
  "var(--msg-brand)",
  "var(--msg-accent-text)",
  "var(--msg-ink-muted)",
] as const;

export function avatarTint(seed: string): string {
  let hash = 0;
  for (let index = 0; index < seed.length; index += 1) {
    hash = (hash * 31 + seed.charCodeAt(index)) >>> 0;
  }
  return AVATAR_TINTS[hash % AVATAR_TINTS.length];
}

/** Unread counts above this are capped, so the pill keeps its shape. */
export function formatUnread(count: number): string {
  return count > 99 ? "99+" : String(count);
}
