# Backend API Contract

Base URL `http://localhost:8080`. All responses share one envelope.

**Success**

```json
{ "success": true, "data": {}, "error": null, "timestamp": "2026-10-03T16:28:48Z" }
```

**Error**

```json
{
  "success": false,
  "data": null,
  "error": { "code": "VALIDATION_ERROR", "message": "...", "details": { "field": "reason" } },
  "timestamp": "2026-10-03T16:28:48Z"
}
```

Branch on `error.code`, never on `message` — the message is for humans and may change.
`details` is only present for validation errors. The HTTP status always matches the code:
`VALIDATION_ERROR` → 400, `UNAUTHORIZED` → 401, `NOT_CHAT_PARTICIPANT` → 403, `NOT_FOUND` → 404,
`PHONE_ALREADY_REGISTERED` / `USERNAME_ALREADY_TAKEN` → 409.

---

## REST

### Auth

| Method | Path | Body | Returns |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | `{phoneNumber, username, password}` | 201 + token |
| POST | `/api/auth/login` | `{phoneNumber, password}` | 200 + token |

`phoneNumber` must be E.164 — `+` then 8–15 digits, no spaces or dashes. `username` is 3–32
characters, letters/digits/dot/underscore. `password` is 8–72 characters.

Both return:

```json
{ "accessToken": "...", "tokenType": "Bearer", "expiresIn": 2592000,
  "user": { "id": "...", "username": "alice", "phoneNumber": "+14155550100",
            "profilePictureUrl": null, "about": null } }
```

Tokens last 30 days. There is no refresh token and no logout — discard the token client-side.

Send it on every other request:

```
Authorization: Bearer <accessToken>
```

### Users

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/users/sync` | resolve local contacts to registered users |
| GET | `/api/users/me` | current user |
| GET | `/api/users/{userId}` | any user's profile |

`POST /api/users/sync` takes `{ "phoneNumbers": ["+1415...", "+4477..."] }`, up to 5000, and
returns only the ones registered here. Numbers in any format other than E.164 fail validation with
400, so normalise before sending.

### Chats

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/chats` | all chats, newest activity first |
| POST | `/api/chats` | create a chat |
| GET | `/api/chats/{chatId}` | one chat summary |
| GET | `/api/chats/{chatId}/messages` | keyset-paginated history |
| POST | `/api/chats/{chatId}/read` | mark read, clears the unread count |

Creating a chat:

```json
{ "groupName": null, "memberIds": ["<other-user-id>"] }
```

Omit `groupName` for a 1:1 chat, which must contain exactly one other member. Supply `groupName` to
create a group of up to 255 members. Creating a 1:1 chat that already exists is **idempotent** — you
get the existing `chatId` back rather than a duplicate, so it is safe to call on every "open chat"
tap.

Chat summary:

```json
{ "id": "...", "group": false, "groupName": null, "groupAvatarUrl": null,
  "lastMessageAt": "2026-10-03T16:28:48.654937Z",
  "lastMessagePreview": "message number 3", "lastMessageSenderId": "...",
  "unreadCount": 2,
  "participants": [ { "userId": "...", "username": "alice",
                      "profilePictureUrl": null, "role": "ADMIN", "online": true } ] }
```

### Message history

`GET /api/chats/{chatId}/messages?limit=30&cursor=<opaque>` walks backwards through history, newest
first. Pass the previous response's `nextCursor` back as `cursor`; omit it for the newest page.
`limit` defaults to 30 and caps at 100. `hasMore` tells you whether to fetch another page.

Treat the cursor as opaque — it is base64 and its format may change. Building one yourself will
fail with 400.

```json
{ "content": [ { "id": "...", "chatId": "...", "senderId": "...", "clientMessageId": "local-uuid",
                 "content": "hello", "type": "TEXT", "replyToId": null,
                 "createdAt": "2026-10-03T16:28:48.654937Z",
                 "receipts": { "total": 2, "delivered": 2, "read": 1 } } ],
  "size": 30, "hasMore": true, "nextCursor": "MTc5MTA..." }
```

`type` is `TEXT`, `IMAGE`, `VIDEO` or `AUDIO`.

`receipts` is only meaningful on **your own** messages — that is what drives the tick marks. For
messages you received it is all zeros, because you already know the state. `content` is decrypted
server-side, so send and expect plain text.

Non-members get 403 `NOT_CHAT_PARTICIPANT`.

---

## WebSocket

`ws://localhost:8080/ws`, STOMP subprotocol `v12.stomp`.

A frame is `command EOL (header EOL)* EOL body NUL`. If you send `content-length`, the `NUL` must
follow the body immediately — no trailing newline.

### Connect

```
CONNECT
accept-version:1.2
host:localhost
heart-beat:0,0
Authorization:Bearer <accessToken>

<NUL>
```

`heart-beat` is required by STOMP 1.2. A valid token is answered with `CONNECTED`; a missing,
malformed or expired token is answered with `ERROR`. Rejection is silent otherwise, so treat `ERROR`
during connect as "re-authenticate".

### Destinations

Per-user channels, using **your own** user id:

| Destination | Payload |
| --- | --- |
| `/topic/user.{userId}.messages` | a new message, including your own |
| `/topic/user.{userId}.receipts` | delivery/read status for a message you sent |
| `/topic/user.{userId}.typing` | `{chatId, userId, at}` |
| `/topic/user.{userId}.presence` | your own presence change |

Per-chat channels:

| Destination | Payload |
| --- | --- |
| `/topic/chat.{chatId}.read` | a receipt from any member |
| `/topic/chat.{chatId}.online-users` | a member's presence change |

Separators are **dots**, not slashes — they map to RabbitMQ topic routing keys, which reject `/`.
Subscribing to `/topic/user.{someoneElse}...` is rejected, so you only ever receive your own
per-user traffic.

### Send

`SEND` to `/app/chat.send`:

```json
{ "chatId": "...", "clientMessageId": "local-uuid", "content": "hello",
  "type": "TEXT", "replyToId": null }
```

`clientMessageId` is required and unique per message. Generate it once on the client and reuse it
across retries — resending the same value is **idempotent** and will not create a duplicate, so you
can retry safely on reconnect. The saved message is echoed back to you on
`/topic/user.{yourId}.messages` alongside every other recipient, so confirm from the same channel
you send on.

### Receipts

`SEND` to `/app/chat.receipt`:

```json
{ "messageId": "...", "status": "DELIVERED", "occurredAt": null }
```

Send `DELIVERED` when a message reaches the device and `READ` when the chat is open. The original
sender receives it on `/topic/user.{senderId}.receipts`. Status only ever moves forward — a stale
`DELIVERED` replayed after a `READ` is ignored, so ticks never regress.

### Typing

`SEND` to `/app/chat.typing` with `{messageId, status, occurredAt}`. Other members receive
`{chatId, userId, at}`. The `messageId` is only used to resolve the chat, so pass any message id
visible in it.

---

## Presence

`isOnline` reflects whether the user has at least one live WebSocket session; on disconnect
`lastSeen` is stamped. Best-effort: if the server dies mid-session, `isOnline` stays `true` until
that user's next connection replaces it.

## Security notes

Message content is encrypted **at rest** with AES-256-GCM and decrypted on read, so responses are
plaintext. This is server-side encryption, **not** end-to-end — the application can read every
message. There is no `publicKey` field and no E2E support. Put TLS in front of this service for
anything beyond local development.