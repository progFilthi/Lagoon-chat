import { NextResponse } from "next/server";
import { backend } from "@/lib/api/backend";
import { ApiError } from "@/lib/api/errors";
import { errorResponse, readJson } from "@/lib/api/respond";
import { getSessionToken } from "@/lib/api/session";

/**
 * Authenticated proxy for the backend's read and mutation endpoints.
 *
 * Auth is deliberately *not* handled here — `login`, `register` and `logout` have
 * their own routes because they are the ones that set or clear the session cookie.
 * Everything else is a straight pass-through: read the cookie, inject the bearer
 * token, unwrap the envelope.
 *
 * This exists so client components never hold the JWT and never see the
 * `ApiResponse` envelope. They get clean data and a real HTTP status, with the
 * backend's `{code, message, details?}` preserved on failure.
 *
 *   app/api/b/chats                     -> GET  /api/chats
 *   app/api/b/chats/<id>/messages?cursor -> GET  /api/chats/<id>/messages
 *   app/api/b/media/download-url        -> POST /api/media/download-url
 *
 * The allowlist is not decoration. Without it this handler is an open relay that
 * will forward any authenticated request to the backend, including to paths we
 * have not reviewed. Adding an endpoint means adding it here on purpose.
 */

type Method = "GET" | "POST";

/** Prefixes the frontend is allowed to reach, matched against the first segment. */
const ALLOWED_PREFIXES = ["chats", "users", "media"] as const;

function assertAllowed(path: string[]): void {
  const head = path[0];
  if (!head || !(ALLOWED_PREFIXES as readonly string[]).includes(head)) {
    throw new ApiError("NOT_FOUND", `No proxy route for /${path.join("/")}.`);
  }
}

async function handle(request: Request, method: Method, rawPath: string[]) {
  const path = rawPath.filter(Boolean).map(decodeURIComponent);
  assertAllowed(path);

  const token = await getSessionToken();
  if (!token) {
    return NextResponse.json(
      { code: "UNAUTHORIZED", message: "Your session has expired. Sign in again." },
      { status: 401 },
    );
  }

  // Preserve the query string verbatim: the history cursor in particular must not
  // be re-encoded, since it is an opaque base64 value from the backend.
  const search = new URL(request.url).search;

  try {
    const body = method === "POST" ? await readJson<unknown>(request) : undefined;
    const data = await backend<unknown>(`/api/${path.join("/")}${search}`, {
      method,
      body,
      token,
    });
    return NextResponse.json(data ?? null);
  } catch (error) {
    return errorResponse(error);
  }
}

export async function GET(request: Request, context: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await context.params;
  return handle(request, "GET", path);
}

export async function POST(request: Request, context: { params: Promise<{ path?: string[] }> }) {
  const { path = [] } = await context.params;
  return handle(request, "POST", path);
}
