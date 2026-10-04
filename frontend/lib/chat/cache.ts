import type { MessagePage, MessageStatus } from "@/lib/api/types";
import type { DisplayMessage } from "./types";
import { laterTick } from "./types";

/**
 * Page-level operations on cached history.
 *
 * The single thing worth internalising here: **the backend returns each page
 * newest-first** (`ORDER BY createdAt DESC`), and an infinite query stacks page 0
 * as the newest. So a new message goes to the *front* of page 0, an optimistic
 * bubble goes to the *end* of page 0, and the render order is assembled by
 * reversing both the pages and each page's contents. Getting this backwards
 * renders history upside down, which is why the reversal is centralised here
 * instead of being repeated in the component.
 */

/**
 * Cached history. The page metadata stays `MessagePage`, but `content` is narrowed to
 * `DisplayMessage` because that is what optimistic sends actually put in there — a `pending`
 * bubble never existed on the server. Declaring it as a separate type keeps `.pending`
 * honest at every call site instead of needing a cast per read.
 */
export type Page = Omit<MessagePage, "content"> & { content: DisplayMessage[] };
export type Pages = Page[];

/** Flattens cached pages into the order they should be drawn in. */
export function chronological(pages: Pages | undefined): DisplayMessage[] {
  if (!pages?.length) return [];
  return [...pages].reverse().flatMap((page) => [...page.content].reverse());
}

export function mapPages(pages: Pages, mutate: (message: DisplayMessage) => DisplayMessage): Pages {
  return pages.map((page) => ({ ...page, content: page.content.map(mutate) }));
}

/** Finds a message anywhere in the loaded history. */
export function findById(pages: Pages | undefined, id: string): DisplayMessage | undefined {
  for (const page of pages ?? []) {
    const hit = page.content.find((message) => message.id === id);
    if (hit) return hit;
  }
  return undefined;
}

export function findByClientMessageId(
  pages: Pages | undefined,
  clientMessageId: string,
): DisplayMessage | undefined {
  for (const page of pages ?? []) {
    const hit = page.content.find((message) => message.clientMessageId === clientMessageId);
    if (hit) return hit;
  }
  return undefined;
}

/**
 * Adds a received message at the newest edge of page 0, skipping it if the history
 * fetch already contained it. The id check matters because a refetch can land
 * between the broadcast arriving and this write being applied.
 */
export function insertNewest(pages: Pages, message: DisplayMessage): Pages {
  const [head, ...rest] = pages;
  if (!head) return pages;
  if (head.content.some((m) => m.id === message.id)) return pages;
  return [{ ...head, content: [message, ...head.content] }, ...rest];
}

/** Appends to the newest edge of page 0 — the live end of an in-flight send. */
export function appendNewest(pages: Pages, message: DisplayMessage): Pages {
  const [head, ...rest] = pages;
  if (!head) return pages;
  return [{ ...head, content: [...head.content, message] }, ...rest];
}

/**
 * Removes the optimistic stand-in for a send once the real message arrives.
 *
 * Matching is on `clientMessageId` rather than id because that is the one value
 * both sides know before the server has assigned anything.
 */
export function removeByClientMessageId(pages: Pages, clientMessageId: string): Pages {
  let changed = false;
  const next = pages.map((page) => {
    const content = page.content.filter((message) => {
      const optimistic = message.pending && message.clientMessageId === clientMessageId;
      if (optimistic) changed = true;
      return !optimistic;
    });
    return changed ? { ...page, content } : page;
  });
  return changed ? next : pages;
}

export function patchByClientMessageId(
  pages: Pages,
  clientMessageId: string,
  patch: (message: DisplayMessage) => DisplayMessage,
): Pages {
  let changed = false;
  const next = pages.map((page) => {
    const content = page.content.map((message) => {
      if (message.clientMessageId !== clientMessageId) return message;
      changed = true;
      return patch(message);
    });
    return changed ? { ...page, content } : page;
  });
  return changed ? next : pages;
}

/** Advances a receipt status, never rewinding it. */
export function advanceTick(pages: Pages, messageId: string, status: MessageStatus): Pages {
  return mapPages(pages, (message) =>
    message.id === messageId
      ? { ...message, tick: laterTick(message.tick ?? "SENT", status) }
      : message,
  );
}