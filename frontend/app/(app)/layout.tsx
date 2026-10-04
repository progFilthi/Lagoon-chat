import { NavRail } from "@/components/nav-rail";
import { Providers } from "@/components/providers";
import { RealtimeProvider } from "@/lib/realtime/realtime-provider";
import { requireSession } from "@/lib/api/session";
import { me } from "@/lib/api/me";

/**
 * Layout for everything behind the session.
 *
 * Providers live here rather than in the root so that `/login` and `/register`
 * do not mount the query client and then sit behind an auth check.
 *
 * The session is verified once per navigation here, which is also what turns a
 * stale or tampered cookie into a redirect instead of a screen full of failed
 * queries.
 *
 * The socket is mounted above the routes rather than inside the chat view so one connection is
 * shared by every screen — the conversation list needs its presence and unread events just as
 * much as an open thread does.
 */
export default async function AppLayout({ children }: LayoutProps<"/">) {
  const token = await requireSession();
  const user = await me(token);

  return (
    <Providers>
      <RealtimeProvider selfId={user.id}>
        <div className="flex min-h-dvh flex-1">
          <NavRail user={user} />
          {children}
        </div>
      </RealtimeProvider>
    </Providers>
  );
}