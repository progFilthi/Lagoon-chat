"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api/client";
import { chatsKey } from "./keys";
import { E164, type UserProfile } from "@/lib/api/types";

/**
 * Starting a 1:1 conversation.
 *
 * Shared by the new-conversation button and the contacts page because the backend offers exactly
 * one way to find a person: `POST /users/sync`, which takes a list of E.164 phone numbers and
 * returns the ones that match an existing account. There is no search-by-username and no
 * directory listing — so a phone number is genuinely the only identifier a user can start from,
 * and pretending otherwise would mean inventing an endpoint.
 *
 * Returns the chat id on success so the caller can navigate straight into the thread.
 */
export function useStartConversation() {
  const router = useRouter();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (phoneNumber: string): Promise<string> => {
      const normalised = phoneNumber.trim().replace(/[\s()-]/g, "");
      if (!E164.test(normalised)) {
        throw new Error("Enter a number in international form, like +14155550123.");
      }

      const matches: UserProfile[] = await api.syncContacts({ phoneNumbers: [normalised] });
      const counterpart = matches[0];
      if (!counterpart) {
        throw new Error("No account is registered with that number.");
      }

      // The backend returns the existing chat for a 1:1 pair rather than a duplicate,
      // so this is safe to call for someone you already talk to.
      const created = await api.createChat({ memberIds: [counterpart.id] });

      // The list is ordered by the server and this added a member, so refetch rather than
      // patching — the preview and ordering are both server-owned.
      void queryClient.invalidateQueries({ queryKey: chatsKey });

      return created.chatId;
    },
    onSuccess: (chatId) => router.push(`/chats/${chatId}`),
  });
}