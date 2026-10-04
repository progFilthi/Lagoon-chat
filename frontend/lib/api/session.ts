import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { SESSION_COOKIE, SESSION_MAX_AGE } from "./backend";

/**
 * The session is a single httpOnly cookie holding the backend's 30-day JWT.
 *
 * The backend has no refresh token and no logout endpoint, so this cookie is the
 * entire session story: when it expires, the only recovery is to sign in again.
 */

export async function getSessionToken(): Promise<string | null> {
  const store = await cookies();
  return store.get(SESSION_COOKIE)?.value ?? null;
}

export async function setSessionCookie(token: string, expiresInSeconds: number): Promise<void> {
  const store = await cookies();
  store.set(SESSION_COOKIE, token, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    // Trust the server's own expiry rather than assuming the 30-day default.
    maxAge: Math.min(expiresInSeconds || SESSION_MAX_AGE, SESSION_MAX_AGE),
  });
}

export async function clearSessionCookie(): Promise<void> {
  const store = await cookies();
  store.delete(SESSION_COOKIE);
}

/**
 * Redirects to the sign-in screen when there is no session.
 *
 * Call from a Server Component or Layout before rendering anything protected, so
 * an unauthenticated visitor never sees a flash of the empty shell.
 */
export async function requireSession(): Promise<string> {
  const token = await getSessionToken();
  if (!token) redirect("/login");
  return token;
}
