import { NextResponse } from "next/server";
import { clearSessionCookie } from "@/lib/api/session";

/**
 * POST /api/auth/logout
 *
 * There is no backend logout — `AuthService` has no revocation and the JWT is
 * valid for its full 30 days regardless. Discarding the token client-side *is* the
 * logout, so this route exists purely to clear the cookie.
 *
 * The consequence is real and worth stating: after signing out, the same token
 * still works if it were recovered. Only a backend change (a denylist, or short
 *-lived tokens plus refresh) can make sign-out actually revoke anything.
 */
export async function POST() {
  await clearSessionCookie();
  return NextResponse.json({ ok: true });
}
