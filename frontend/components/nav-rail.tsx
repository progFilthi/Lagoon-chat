"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { initials, avatarTint } from "@/lib/format";
import type { UserProfile } from "@/lib/api/types";

/**
 * The 76px left rail: brand mark, primary destinations, then the account chip
 * pinned to the bottom.
 *
 * Deliberately no calls destination — there is no calls feature in v1.
 */

const DESTINATIONS = [
  { href: "/chats", label: "Chats", icon: "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2Z" },
  { href: "/contacts", label: "Contacts", icon: "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM22 21v-2a4 4 0 0 0-3-3.9" },
  { href: "/settings", label: "Settings", icon: "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-2.9 1.2V21a2 2 0 1 1-4 0v-.1A1.7 1.7 0 0 0 8 19.4a1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0-1.2-2.9H2a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 3.7 8a1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H8a1.7 1.7 0 0 0 1-1.5V2a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V8a1.7 1.7 0 0 0 1.5 1H22a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z" },
] as const;

export function NavRail({ user }: { user: UserProfile }) {
  const pathname = usePathname();

  return (
    <nav className="flex w-[76px] shrink-0 flex-col items-center gap-2.5 border-r border-msg-hairline bg-msg-ground pt-5 pb-[18px]">
      <div className="flex size-10 items-center justify-center rounded-msg-sm bg-msg-accent">
        <span className="text-msg-xl leading-none font-extrabold text-msg-on-accent">L</span>
      </div>

      <div className="h-px w-8 bg-msg-hairline" />

      <ul className="flex flex-col gap-2.5">
        {DESTINATIONS.map((destination) => {
          const active = pathname === destination.href || pathname.startsWith(`${destination.href}/`);
          return (
            <li key={destination.href}>
              <Link
                href={destination.href}
                aria-label={destination.label}
                aria-current={active ? "page" : undefined}
                title={destination.label}
                className={[
                  "flex size-12 items-center justify-center rounded-full transition-colors",
                  active
                    ? "bg-msg-accent-tint text-msg-ink"
                    : "text-msg-ink-muted hover:bg-msg-surface hover:text-msg-ink",
                ].join(" ")}
              >
                <svg
                  width="22"
                  height="22"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                >
                  <path d={destination.icon} />
                </svg>
              </Link>
            </li>
          );
        })}
      </ul>

      <div className="flex-1" />

      <Link
        href="/settings"
        title={user.username}
        className="relative flex size-10 items-center justify-center rounded-full text-msg-sm font-bold text-msg-ground"
        style={{ backgroundColor: avatarTint(user.id) }}
      >
        {initials(user.username)}
        {user.online ? (
          <span className="absolute -right-0.5 -bottom-0.5 size-3 rounded-full border-2 border-msg-ground bg-msg-accent" />
        ) : null}
      </Link>
    </nav>
  );
}
