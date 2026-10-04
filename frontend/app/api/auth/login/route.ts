import { NextResponse } from "next/server";
import { backend } from "@/lib/api/backend";
import { errorResponse, readJson } from "@/lib/api/respond";
import { setSessionCookie } from "@/lib/api/session";
import type { AuthResponse, LoginRequest } from "@/lib/api/types";

/**
 * POST /api/auth/login
 *
 * Proxies `POST /api/auth/login` on the backend, stores the JWT in an httpOnly
 * cookie, and hands the browser the user profile only. The token is never in the
 * response body, so client code has no way to read or leak it.
 */
export async function POST(request: Request) {
  const body = await readJson<LoginRequest>(request);

  try {
    const auth = await backend<AuthResponse>("/api/auth/login", {
      method: "POST",
      body,
    });

    await setSessionCookie(auth.accessToken, auth.expiresIn);

    return NextResponse.json({ user: auth.user });
  } catch (error) {
    return errorResponse(error);
  }
}
