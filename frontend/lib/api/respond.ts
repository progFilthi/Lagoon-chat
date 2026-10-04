import { NextResponse } from "next/server";
import { ApiError } from "./errors";

/**
 * The failure shape every proxy route returns: a real HTTP status matching the
 * error code, plus `{code, message, details?}`.
 *
 * `code` is the contract — branch on it and never on `message`, which is written
 * for humans and may change.
 */
export function errorResponse(error: unknown): NextResponse {
  if (error instanceof ApiError) {
    return NextResponse.json(
      {
        code: error.code,
        message: error.message,
        ...(error.details ? { details: error.details } : {}),
      },
      { status: error.status },
    );
  }
  return NextResponse.json(
    { code: "INTERNAL_ERROR", message: "Something went wrong. Try again." },
    { status: 500 },
  );
}

/** For handlers that read a JSON body that may not be JSON. */
export async function readJson<T>(request: Request): Promise<T> {
  try {
    return (await request.json()) as T;
  } catch {
    throw new ApiError("MALFORMED_REQUEST", "Expected a JSON body.");
  }
}
