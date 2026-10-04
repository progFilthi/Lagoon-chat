/**
 * The backend wire contract.
 *
 * Every shape here was read out of the Spring source rather than inferred from
 * prose, so these are the types the server actually serialises. Where the docs
 * and the code disagreed, the code won — the notable cases are called out below.
 */

/** Every REST response arrives in this envelope. Our route handlers unwrap it. */
export interface ApiEnvelope<T> {
  success: boolean;
  data: T | null;
  error: ApiErrorBody | null;
  timestamp: string;
}

export interface ApiErrorBody {
  /** Branch on this. Never on `message` — that is human-facing and may change. */
  code: ErrorCode;
  message: string;
  /** Present only for validation failures. */
  details?: Record<string, string> | null;
}

/**
 * Mirrors `ErrorCode` in the backend. The HTTP status always matches the code,
 * so this doubles as the status mapping.
 */
export type ErrorCode =
  | "VALIDATION_ERROR"
  | "MALFORMED_REQUEST"
  | "UNAUTHORIZED"
  | "FORBIDDEN"
  | "NOT_FOUND"
  | "CONFLICT"
  | "PHONE_ALREADY_REGISTERED"
  | "USERNAME_ALREADY_TAKEN"
  | "INVALID_CREDENTIALS"
  | "CHAT_ACCESS_DENIED"
  | "NOT_CHAT_PARTICIPANT"
  | "MEDIA_NOT_CONFIGURED"
  | "RATE_LIMITED"
  | "INTERNAL_ERROR";

export const ERROR_STATUS: Record<ErrorCode, number> = {
  VALIDATION_ERROR: 400,
  MALFORMED_REQUEST: 400,
  UNAUTHORIZED: 401,
  INVALID_CREDENTIALS: 401,
  FORBIDDEN: 403,
  CHAT_ACCESS_DENIED: 403,
  NOT_CHAT_PARTICIPANT: 403,
  NOT_FOUND: 404,
  CONFLICT: 409,
  PHONE_ALREADY_REGISTERED: 409,
  USERNAME_ALREADY_TAKEN: 409,
  RATE_LIMITED: 429,
  INTERNAL_ERROR: 500,
  MEDIA_NOT_CONFIGURED: 503,
};

/**
 * The one user shape in the API. Login, registration, `/users/me`,
 * `/users/{userId}` and every element of `/users/sync` all return exactly this,
 * so a single type covers all of them.
 */
export interface UserProfile {
  id: string;
  username: string;
  phoneNumber: string;
  profilePictureUrl: string | null;
  about: string | null;
  /** False and lastSeen null until this client holds a socket. */
  online: boolean;
  lastSeen: string | null;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: "Bearer";
  /** Seconds. 30 days. */
  expiresIn: number;
  user: UserProfile;
}

export interface LoginRequest {
  phoneNumber: string;
  password: string;
}

export interface RegisterRequest {
  phoneNumber: string;
  username: string;
  password: string;
}

/**
 * E.164, enforced server-side by `^\+[1-9]\d{7,14}$` on both register and
 * contact sync. Normalise before sending; anything else fails validation.
 */
export const E164 = /^\+[1-9]\d{7,14}$/;

/** Mirrors the backend's `@Size` and `@Pattern` constraints exactly. */
export const USERNAME_RE = /^[a-zA-Z0-9_.]+$/;
export const USERNAME_MIN = 3;
export const USERNAME_MAX = 32;
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 72;

export type ChatRole = "ADMIN" | "MEMBER";

export interface ChatParticipant {
  userId: string;
  username: string;
  profilePictureUrl: string | null;
  role: ChatRole;
  online: boolean;
}

export interface ChatSummary {
  id: string;
  group: boolean;
  groupName: string | null;
  groupAvatarUrl: string | null;
  lastMessageAt: string | null;
  /**
   * Generated server-side by `ChatService.previewOf`. An attachment reads as
   * "Photo", "Video" or "Audio", and becomes "Photo: <caption>" when captioned.
   * There is no sender prefix — build that client-side from lastMessageSenderId.
   */
  lastMessagePreview: string | null;
  lastMessageSenderId: string | null;
  unreadCount: number;
  participants: ChatParticipant[];
}

export interface CreateChatRequest {
  /** Omit for a 1:1 chat, which must contain exactly one other member. */
  groupName?: string | null;
  memberIds: string[];
}

export interface CreateChatResponse {
  chatId: string;
  group: boolean;
  groupName: string | null;
  createdAt: string;
  memberIds: string[];
}

/** Cursor page over message history. Treat `nextCursor` as opaque. */
export interface MessagePage {
  content: Message[];
  size: number;
  hasMore: boolean;
  nextCursor: string | null;
}

export type MessageType = "TEXT" | "IMAGE" | "VIDEO" | "AUDIO";
export type MessageStatus = "SENT" | "DELIVERED" | "READ";

export interface ReceiptSummary {
  /** Recipient count, so 0 on your own... no: this is the sender's view only. */
  total: number;
  /** Counts DELIVERED *or beyond*, so it includes everything already read. */
  delivered: number;
  read: number;
}

/**
 * The only message shape in the API. The `chat.send` broadcast is this same
 * record, so an optimistic bubble reconciles on clientMessageId with no refetch.
 */
export interface Message {
  id: string;
  chatId: string;
  senderId: string;
  /** Generated once client-side, unique per message. Retrying with the same
   *  value is idempotent, so it is safe to resend on reconnect. */
  clientMessageId: string;
  /** Decrypted server-side. The caption for an attachment, which may be empty. */
  content: string;
  type: MessageType;
  replyToId: string | null;
  createdAt: string;
  /**
   * Populated only on your own messages — `MessageService.project` zeroes it for
   * anyone else's, because you already know the state and populating it would
   * tell a group who has read what. This is what drives the tick marks.
   */
  receipts: ReceiptSummary;
}

/** The `/app/chat.send` frame. `content` is the caption and may be empty. */
export interface SendMessagePayload {
  chatId: string;
  clientMessageId: string;
  content: string;
  type: MessageType;
  replyToId: string | null;
  /** Required for IMAGE/VIDEO/AUDIO, rejected on TEXT. Must be your own prefix. */
  mediaKey?: string | null;
}

/**
 * The `/app/chat.receipt` and `/app/chat.typing` frame — both deserialize
 * `ReceiptEventPayload` on the server, so `chatMemberIds` has to be present even
 * though the server ignores it on send.
 */
export interface ReceiptPayload {
  messageId: string;
  status: MessageStatus;
  chatMemberIds: string[];
  occurredAt: string | null;
}

/** What other members receive on the typing channel. */
export interface TypingSignal {
  chatId: string;
  userId: string;
  at: string;
}

/** Broadcast on `/topic/user.{yourId}.read-state`. `userId` is always you. */
export interface ReadStateEvent {
  chatId: string;
  userId: string;
  lastReadAt: string;
}

/** Broadcast on `/topic/user.{yourId}.errors`. The socket stays open. */
export interface SocketErrorEvent {
  code: ErrorCode;
  message: string;
  /** The inbound destination that was rejected. */
  destination: string;
  /** Echoes the STOMP `receipt` header, so you can fail one specific send. */
  receipt: string | null;
}

/** Broadcast on `/topic/user.{yourId}.presence`. */
export interface PresenceEvent {
  userId: string;
  username: string;
  online: boolean;
  lastSeen: string | null;
  chatIds: string[];
}

export interface SyncContactsRequest {
  /** Up to 5000, all E.164. */
  phoneNumbers: string[];
}

/* ---------------------------------------------------------------- media --- */

export interface UploadUrlRequest {
  /** Must be in the server allowlist, or 400. */
  contentType: string;
  /** Advisory only — a presigned PUT cannot enforce a byte range. */
  sizeBytes: number;
}

export interface UploadUrlResponse {
  /** Becomes `mediaKey` when sending. Never expose it as a URL. */
  objectKey: string;
  /** PUT the bytes here with exactly `headers` and nothing else. */
  uploadUrl: string;
  headers: Record<string, string>;
  expiresInSeconds: number;
  expiresAt: string;
}

export interface CompleteUploadResponse {
  /** False means the stored object was deleted. Do not send the message. */
  verified: boolean;
  sizeBytes: number | null;
  contentType: string | null;
  reason: string | null;
}

export interface DownloadUrlResponse {
  downloadUrl: string;
  expiresInSeconds: number;
  expiresAt: string;
}

/** Authorised by chat membership, never by key. */
export interface DownloadUrlRequest {
  messageId: string;
}

/** Defaults mirrored from application.yaml. */
export const MEDIA_MAX_BYTES = 26_214_400;
export const MEDIA_ALLOWED_CONTENT_TYPES = [
  "image/jpeg",
  "image/png",
  "image/webp",
  "image/gif",
  "video/mp4",
  "video/webm",
  "audio/mpeg",
  "audio/mp4",
  "audio/ogg",
  "audio/webm",
] as const;
