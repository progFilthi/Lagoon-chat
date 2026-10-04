import { ApiError } from "./errors";
import type {
  ChatSummary,
  CreateChatRequest,
  CreateChatResponse,
  DownloadUrlRequest,
  DownloadUrlResponse,
  MessagePage,
  SyncContactsRequest,
  UploadUrlRequest,
  UploadUrlResponse,
  CompleteUploadResponse,
  UserProfile,
} from "./types";

/**
 * The browser's view of the backend.
 *
 * Every call goes to our own `/api/b/*` proxy, which holds the token and unwraps
 * the envelope — so these functions resolve to plain domain objects and reject
 * with `ApiError` carrying the backend's `code`. Nothing here ever sees a JWT or
 * an `ApiResponse`.
 */

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/b${path}`, {
    ...init,
    headers: {
      Accept: "application/json",
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      ...init?.headers,
    },
    // Auth state and messages must never be served stale.
    cache: "no-store",
  });

  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as
      | { code?: string; message?: string; details?: Record<string, string> }
      | null;
    throw new ApiError(
      (body?.code as ApiError["code"]) ?? "INTERNAL_ERROR",
      body?.message ?? `Request failed with status ${response.status}`,
      body?.details,
    );
  }

  if (response.status === 204) return null as T;
  return (await response.json()) as T;
}

function post<T>(path: string, body: unknown): Promise<T> {
  return call<T>(path, { method: "POST", body: JSON.stringify(body) });
}

export const api = {
  me: () => call<UserProfile>("/users/me"),
  user: (userId: string) => call<UserProfile>(`/users/${userId}`),
  syncContacts: (body: SyncContactsRequest) => post<UserProfile[]>("/users/sync", body),

  chats: () => call<ChatSummary[]>("/chats"),
  chat: (chatId: string) => call<ChatSummary>(`/chats/${chatId}`),
  createChat: (body: CreateChatRequest) => post<CreateChatResponse>("/chats", body),
  markRead: (chatId: string) => post<void>(`/chats/${chatId}/read`, {}),

  /**
   * One page of history, newest first — the backend orders `createdAt desc`, so
   * reverse before rendering. The cursor is opaque and passed straight back.
   *
   * `limit` is clamped server-side to 1..100 rather than rejected, so a larger
   * value silently becomes 100. That is intentional; do not "fix" it here.
   */
  messages: (chatId: string, cursor?: string | null, limit = 30) => {
    const search = new URLSearchParams();
    if (cursor) search.set("cursor", cursor);
    if (limit) search.set("limit", String(limit));
    const qs = search.toString();
    return call<MessagePage>(`/chats/${chatId}/messages${qs ? `?${qs}` : ""}`);
  },

  /* media */

  /**
   * Step 1 of an upload: get a signed URL. Signing is local computation on the
   * server, so this succeeds whenever media is configured.
   */
  uploadUrl: (body: UploadUrlRequest) => post<UploadUrlResponse>("/media/upload-url", body),

  /**
   * Step 3: re-reads the stored object and deletes it if the real size or type
   * contradicts the declaration. Not optional — check `verified` before sending.
   */
  completeUpload: (objectKey: string) => post<CompleteUploadResponse>("/media/complete", { objectKey }),

  /**
   * A signed GET for a message's attachment, authorised by chat membership.
   * Requested per bubble, since the URL is not cache-friendly by design.
   */
  downloadUrl: (messageId: string) =>
    post<DownloadUrlResponse>("/media/download-url", { messageId } satisfies DownloadUrlRequest),
};

/**
 * Uploads the bytes and confirms them, returning the key to send as `mediaKey`.
 *
 * The PUT must carry exactly the headers the server returned — the content type is
 * part of the signature, so S3 rejects a mismatched one — and nothing else.
 */
export async function uploadAttachment(file: File): Promise<string> {
  const presigned = await api.uploadUrl({
    contentType: file.type,
    sizeBytes: file.size,
  });

  const putResponse = await fetch(presigned.uploadUrl, {
    method: "PUT",
    headers: presigned.headers,
    body: file,
  });
  if (!putResponse.ok) {
    throw new ApiError(
      "INTERNAL_ERROR",
      `Upload failed with status ${putResponse.status}. Check the bucket region and IAM policy.`,
    );
  }

  const complete = await api.completeUpload(presigned.objectKey);
  if (!complete.verified) {
    throw new ApiError(
      "VALIDATION_ERROR",
      complete.reason ?? "The uploaded file did not match what was declared, so it was discarded.",
    );
  }

  return presigned.objectKey;
}
