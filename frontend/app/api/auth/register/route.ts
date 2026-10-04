import { NextResponse } from "next/server";
import { backend } from "@/lib/api/backend";
import { errorResponse, readJson } from "@/lib/api/respond";
import { setSessionCookie } from "@/lib/api/session";
import type { AuthResponse, RegisterRequest } from "@/lib/api/types";

/**
 * POST /api/auth/register
 *
 * The backend answers 201 with the same `AuthResponse` shape as login, so the
 * session is established here too and the user lands straight in the app.
 */
export async function POST(request: Request) {
  const body = await readJson<RegisterRequest>(request);

  try {
    const auth = await backend<AuthResponse>("/api/auth/register", {
      method: "POST",
      body,
    });

    await setSessionCookie(auth.accessToken, auth.expiresIn);

    return NextResponse.json({ user: auth.user }, { status: 201 });
  } catch (error) {
    return errorResponse(error);
  }
}
