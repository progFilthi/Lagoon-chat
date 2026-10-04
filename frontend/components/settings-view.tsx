"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { avatarTint, initials, formatLastSeen } from "@/lib/format";
import { useRealtime } from "@/lib/realtime/realtime-provider";
import type { UserProfile } from "@/lib/api/types";

/**
 * Settings.
 *
 * Scoped honestly to what exists: there is no profile-update endpoint in this API, so nothing
 * here is editable, and inventing a form that silently fails on submit would be worse than
 * showing read-only detail. The one real action is signing out.
 *
 * The connection state is surfaced because it is the answer to the most common "is this thing
 * broken?" question about a chat app — the socket state is otherwise invisible.
 */
export function SettingsView({ user }: { user: UserProfile }) {
  const router = useRouter();
  const { status } = useRealtime();
  const [signingOut, setSigningOut] = useState(false);

  async function signOut() {
    setSigningOut(true);
    // Clear the cookie server-side; the redirect then hits a session check that fails cleanly.
    await fetch("/api/auth/logout", { method: "POST" }).catch(() => null);
    router.replace("/login");
    router.refresh();
  }

  return (
    <main className="min-w-0 flex-1 overflow-y-auto bg-msg-surface">
      <div className="mx-auto max-w-2xl px-8 py-8">
        <h1 className="text-msg-2xl font-extrabold tracking-tight text-msg-ink">Settings</h1>

        <section className="mt-6 flex items-center gap-4 rounded-msg-md bg-msg-ground p-5">
          <div
            className="flex size-14 shrink-0 items-center justify-center rounded-full text-msg-lg font-bold text-msg-ground"
            style={{ backgroundColor: avatarTint(user.id) }}
          >
            {initials(user.username)}
          </div>
          <div className="min-w-0">
            <p className="truncate text-msg-lg font-extrabold text-msg-ink">{user.username}</p>
            <p className="truncate text-msg-sm text-msg-ink-muted">{user.phoneNumber}</p>
          </div>
        </section>

        <dl className="mt-6 overflow-hidden rounded-msg-md bg-msg-ground">
          <Row label="Phone number" value={user.phoneNumber} />
          <Row label="About" value={user.about ?? "Not set"} />
          <Row
            label="Presence"
            value={user.online ? "Online" : formatLastSeen(user.lastSeen)}
          />
          <Row
            label="Realtime"
            value={
              status === "connected"
                ? "Connected"
                : status === "connecting"
                  ? "Connecting…"
                  : status === "reconnecting"
                    ? "Reconnecting…"
                    : "Offline"
            }
          />
        </dl>

        <p className="mt-4 text-msg-sm leading-[18px] text-msg-ink-muted">
          Profile editing is not available — this API has no endpoint to update an account.
        </p>

        <button
          type="button"
          onClick={() => void signOut()}
          disabled={signingOut}
          className="mt-8 h-10 cursor-pointer rounded-full border border-msg-hairline bg-msg-ground px-5 text-msg-sm font-semibold text-msg-live-text disabled:opacity-50"
        >
          {signingOut ? "Signing out…" : "Sign out"}
        </button>
      </div>
    </main>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-t border-msg-hairline px-5 py-3.5 first:border-t-0">
      <dt className="shrink-0 text-msg-sm font-semibold text-msg-ink-muted">{label}</dt>
      <dd className="truncate text-msg-base text-msg-ink">{value}</dd>
    </div>
  );
}