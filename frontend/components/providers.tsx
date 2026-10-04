"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import type { ReactNode } from "react";

/**
 * Query defaults tuned for this backend rather than for a generic REST API.
 *
 * `refetchOnWindowFocus` is off because presence and incoming messages arrive
 * over the socket, which is strictly fresher than a focus-triggered refetch —
 * re-running the chat list query on tab focus would only add load and undo the
 * ordering the socket just established.
 *
 * Messages are never stale for 30s: a `chat.send` broadcast lands in the cache
 * directly, so a refetch would only risk reordering a list the socket just
 * settled.
 */
export function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30_000,
            refetchOnWindowFocus: false,
            retry: (failureCount, error) => {
              // Never retry an auth or permission failure; it cannot succeed on a
              // second attempt and the user has to sign in again or stop.
              const code = (error as { code?: string })?.code;
              if (code === "UNAUTHORIZED" || code === "INVALID_CREDENTIALS") return false;
              if (code === "NOT_CHAT_PARTICIPANT" || code === "FORBIDDEN") return false;
              return failureCount < 2;
            },
          },
        },
      }),
  );

  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
