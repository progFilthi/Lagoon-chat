import { ERROR_STATUS, type ApiErrorBody, type ErrorCode } from "./types";

/**
 * A failed backend call, carrying the machine-readable code.
 *
 * `code` is the only thing safe to branch on. `message` is written for humans and
 * may change; `details` is present only on validation failures and maps a field
 * to the reason it was rejected.
 */
export class ApiError extends Error {
  readonly code: ErrorCode;
  readonly status: number;
  readonly details: Record<string, string> | null;

  constructor(code: ErrorCode, message: string, details?: Record<string, string> | null) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = ERROR_STATUS[code] ?? 500;
    this.details = details ?? null;
  }

  /** The caller presented no usable token, or it expired. */
  get isAuthFailure(): boolean {
    return this.code === "UNAUTHORIZED" || this.code === "INVALID_CREDENTIALS";
  }

  static from(error: ApiErrorBody): ApiError {
    return new ApiError(error.code, error.message, error.details);
  }
}

/** Field-level reasons for a VALIDATION_ERROR, if the server sent any. */
export function fieldErrors(error: unknown): Record<string, string> {
  return error instanceof ApiError && error.details ? error.details : {};
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}
