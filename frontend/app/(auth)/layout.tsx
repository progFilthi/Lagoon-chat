import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { BrandPanel } from "@/components/brand-panel";
import { getSessionToken } from "@/lib/api/session";

export const metadata: Metadata = {
  title: "Lagoon",
};

/**
 * Layout for the unauthenticated screens only.
 *
 * Providers deliberately live in the `(app)` group rather than here, so signing
 * in does not re-render the whole tree behind an auth check. This layout also
 * bounces an already-signed-in visitor straight to the app.
 */
export default async function AuthLayout({ children }: LayoutProps<"/">) {
  if (await getSessionToken()) redirect("/chats");

  return (
    <div className="flex min-h-dvh flex-1">
      <BrandPanel />

      <main className="flex flex-1 flex-col bg-msg-ground px-6 py-10 sm:px-8 sm:py-16">
        {/* The brand panel is gone below lg, so the mark comes with it. */}
        <div className="mb-12 flex items-center gap-3 lg:hidden">
          <div className="flex size-10 shrink-0 items-center justify-center rounded-msg-sm bg-msg-accent">
            <span className="text-msg-xl leading-none font-extrabold text-msg-on-accent">L</span>
          </div>
          <span className="text-msg-xl leading-7 font-extrabold tracking-tight text-msg-ink">
            Lagoon
          </span>
        </div>

        <div className="flex flex-1 items-center">{children}</div>
      </main>
    </div>
  );
}
