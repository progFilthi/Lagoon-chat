"use client";

import { useState } from "react";
import { api } from "@/lib/api/client";
import { useStartConversation } from "@/lib/chat/use-start-conversation";
import { avatarTint, initials, formatLastSeen } from "@/lib/format";
import { E164, type UserProfile } from "@/lib/api/types";

const MAX_BATCH = 5000;

/**
 * Contacts.
 *
 * The backend has no directory to browse — the only way to resolve a person is
 * `POST /users/sync`, which matches E.164 numbers against registered accounts and returns only
 * the ones that matched. So this screen is a number matcher rather than a contact list, and it
 * is honest about that: unmatched numbers are reported as unmatched instead of being hidden.
 */
export function ContactsView({ selfId }: { selfId: string }) {
  const [raw, setRaw] = useState("");
  const [matches, setMatches] = useState<UserProfile[] | null>(null);
  const start = useStartConversation();

  function parseNumbers(): string[] {
    return raw
      .split(/[\s,;]+/)
      .map((value) => value.replace(/[\s()-]/g, ""))
      .filter(Boolean);
  }

  const numbers = parseNumbers();
  const invalid = numbers.filter((number) => !E164.test(number));
  const valid = numbers.filter((number) => E164.test(number));

  async function sync() {
    // Rejected client-side so a malformed batch does not cost a round trip; the server
    // validates too, but failing here explains which entry was wrong.
    if (invalid.length > 0) {
      setMatches(null);
      return;
    }
    if (valid.length === 0) return;

    try {
      const found = await api.syncContacts({ phoneNumbers: valid.slice(0, MAX_BATCH) });
      setMatches(found.filter((user) => user.id !== selfId));
    } catch {
      setMatches(null);
    }
  }

  return (
    <main className="min-w-0 flex-1 overflow-y-auto bg-msg-surface">
      <div className="mx-auto max-w-2xl px-8 py-8">
        <h1 className="text-msg-2xl font-extrabold tracking-tight text-msg-ink">Contacts</h1>
        <p className="mt-1 text-msg-base leading-6 text-msg-ink-muted">
          Paste numbers in international form, one per line. Only numbers with an account here
          will match.
        </p>

        <textarea
          value={raw}
          onChange={(event) => setRaw(event.target.value)}
          rows={6}
          placeholder={"+14155550123\n+442071838750"}
          aria-label="Phone numbers to match"
          className="mt-4 w-full resize-y rounded-msg-md bg-msg-ground p-3 text-msg-base leading-6 outline-none placeholder:text-msg-ink-muted"
        />

        {invalid.length > 0 ? (
          <p role="alert" className="mt-2 text-msg-sm text-msg-live-text">
            {invalid.length === 1
              ? `${invalid[0]} is not in international form.`
              : `${invalid.length} entries are not in international form: ${invalid.slice(0, 3).join(", ")}.`}
          </p>
        ) : null}

        <button
          type="button"
          onClick={() => void sync()}
          disabled={valid.length === 0 || invalid.length > 0}
          className="mt-3 h-10 cursor-pointer rounded-full bg-msg-accent px-5 text-msg-sm font-semibold text-msg-on-accent disabled:cursor-not-allowed disabled:opacity-50"
        >
          Find contacts
        </button>

        {matches ? (
          <section className="mt-8">
            <h2 className="text-msg-lg font-extrabold tracking-tight text-msg-ink">
              {matches.length} {matches.length === 1 ? "match" : "matches"}
            </h2>

            <ul className="mt-3 overflow-hidden rounded-msg-md bg-msg-ground">
              {matches.map((user) => (
                <li key={user.id} className="flex items-center gap-3 border-t border-msg-hairline px-4 py-3 first:border-t-0">
                  <div
                    className="flex size-10 shrink-0 items-center justify-center rounded-full text-msg-sm font-bold text-msg-ground"
                    style={{ backgroundColor: avatarTint(user.id) }}
                  >
                    {initials(user.username)}
                  </div>

                  <div className="min-w-0 flex-1">
                    <p className="truncate text-msg-base font-bold text-msg-ink">{user.username}</p>
                    <p className="truncate text-msg-sm text-msg-ink-muted">
                      {user.phoneNumber} ·{" "}
                      {user.online ? "online" : formatLastSeen(user.lastSeen)}
                    </p>
                  </div>

                  <button
                    type="button"
                    onClick={() => start.mutate(user.phoneNumber)}
                    disabled={start.isPending}
                    className="h-8 shrink-0 cursor-pointer rounded-full bg-msg-accent-tint px-4 text-msg-sm font-semibold text-msg-ink disabled:opacity-50"
                  >
                    Message
                  </button>
                </li>
              ))}
            </ul>

            {valid.length > matches.length ? (
              <p className="mt-3 text-msg-sm leading-[18px] text-msg-ink-muted">
                {valid.length - matches.length} of {valid.length} numbers have no account here.
              </p>
            ) : null}
          </section>
        ) : null}
      </div>
    </main>
  );
}