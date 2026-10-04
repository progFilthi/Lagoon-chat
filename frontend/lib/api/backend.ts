import { ApiError } from "./errors";
import type { ApiEnvelope } from "./types";

/**
 * Server-side client for the Spring backend.
 *
 * This is only ever called from a Route Handler or Server Component, never from
 * the browser: the 30-day JWT lives in an httpOnly cookie, so the token is
 * injected here and the response envelope is unwrapped before anything reaches
 * client code. Client components therefore receive clean data and only ever see
 * a real HTTP status plus a JSON `{code, message, details?}` body on failure.
 */

export const BACKEND_URL =
  process.env.BACKEND_URL?.replace(/\/$/, "") ?? "http://localhost:8080";

/** Name of the httpOnly cookie holding the JWT. */
export const SESSION_COOKIE = "lagoon_session";

/** 30 days, matching `app.jwt.access-token-ttl`. */
export const SESSION_MAX_AGE = 60 * 60 * 24 * 30;

interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
  token?: string | null;
  /** Forward the browser's headers, e.g. cookies, on server-rendered fetches. */
  headers?: HeadersInit;
  signal?: AbortSignal;
}

/**
 * Calls the backend and returns the unwrapped `data`.
 *
 * Throws `ApiError` on any non-2xx response, and on a 2xx whose envelope reports
 * `success: false` — the backend always sets the HTTP status from the error code,
 * but checking the envelope too means a proxy that rewrites a status cannot make
 * a failure look like a success.
 */
export async function backend<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, token, headers, signal } = options;

  const requestHeaders: Record<string, string> = {
    Accept: "application/json",
    ...(headers as Record<string, string> | undefined),
  };
  if (body !== undefined) requestHeaders["Content-Type"] = "application/json";
  if (token) requestHeaders.Authorization = `Bearer ${token}`;

  let response: Response;
  try {
    response = await fetch(`${BACKEND_URL}${path}`, {
      method,
      headers: requestHeaders,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
      // The token is not a cookie as far as the backend is concerned, so this
      // must never be served from the Next data cache.
      cache: "no-store",
    });
  } catch {
    throw new ApiError(
      "INTERNAL_ERROR",
      `Could not reach the backend at ${BACKEND_URL}. Is it running?`,
    );
  }

  const text = await response.text();
  let envelope: ApiEnvelope<T> | null = null;
  if (text) {
    try {
      envelope = JSON.parse(text) as ApiEnvelope<T>;
    } catch {
      // Fall through to the status-based error below.
    }
  }

  if (!response.ok || !envelope || envelope.success === false) {
    throw envelope?.error
      ? ApiError.from(envelope.error)
      : new ApiError(
          response.status === 401 ? "UNAUTHORIZED" : "INTERNAL_ERROR",
          envelope?.error?.message ?? `Request failed with status ${response.status}`,
        );
  }

  // `ok()` with no payload serialises data as null, e.g. POST /chats/{id}/read.
  return envelope.data as T;
}

/** Builds a query string, dropping undefined and empty values. */
export function query(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === "") continue;
    search.set(key, String(value));
  }
  const serialised = search.toString();
  return serialised ? `?${serialised}` : "";
}
