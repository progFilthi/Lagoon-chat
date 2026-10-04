/**
 * Query keys, in one place so a socket handler and a component cannot drift apart.
 *
 * TanStack matches key arrays by prefix, so `messageKeys.all` is deliberately a
 * prefix of `messageKeys.list`. That is what lets the socket invalidate history for
 * every open chat with one call while still letting a single chat be targeted.
 */

/** The conversation list. Shared by `/chats`, the list pane, and the socket. */
export const chatsKey = ["chats"] as const;

/**
 * One chat's own metadata, under a different root from `chatsKey` so that a
 * `setQueryData(chatsKey)` can never collide with a single chat's entry.
 */
export const chatKey = (chatId: string) => ["chat", chatId] as const;

export const messageKeys = {
  all: ["messages"] as const,
  /** Prefix matching every chat's history. */
  allChats: () => ["messages"] as const,
  list: (chatId: string) => ["messages", chatId] as const,
} as const;

export const contactsKey = ["contacts"] as const;