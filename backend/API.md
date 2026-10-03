# Backend API Contract

Base URL `http://localhost:8080`. All responses use the same envelope:

```json
{ "success": true, "data": { }, "error": null, "timestamp": "2026-10-03T15:00:00Z" }
```

Errors replace `data` with `error`:

```json
{ "success": false, "data": null,
  "error": { "code": "VALIDATION_ERROR", "message": "...", "details": { "field": "reason" } },
  "timestamp": "2026-10-03T15:00:00Z" }
```

`error.code` is a stable machine-readable string from `ErrorCode`; only branch on that, never on
`message`. HTTP status always matches the error (`VALIDATION_ERROR` → 400, `NOT_FOUND` → 404,
`NOT_CHAT_PARTICIPANT` → 403, `PHONE_ALREADY_REGISTERED` → 409).

## Authentication

Send the JWT on every protected call:

```
Authorization: Bearer <accessToken>
```

Access tokens last 30 days. There is no refresh token and no logout endpoint — discard the token
client-side.

## REST

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | no | `{phoneNumber, username, password}` → 201 + token |
| POST | `/api/auth/login` | no | `{phoneNumber, password}` → 200 + token |
| POST | `/api/users/sync` | yes | `{phoneNumbers: string[]}` → contacts registered here |
| GET | `/api/users/me` | yes | current user |
| GET | `/api/users/{userId}` | yes | any user profile |
| GET | `/api/chats` | yes | all chats, newest activity first, with unread counts |
| POST | `/api/chats` | yes | create a chat |
| GET | `/api/chats/{chatId}` | yes | one chat summary |
| GET | `/api/chats/{chatId}/messages` | yes | keyset-paginated history |
| POST | `/api/chats/{chatId}/read` | yes | mark the chat read (clears unread count) |

`phoneNumber` must be E.164: `+` then 8–15 digits, no spaces or dashes. Send contacts in the same
format or `/sync` returns 400.

### Creating a chat

```json
{ "groupName": null, "memberIds": ["<other-user-id>"] }
```

Omit `groupName` for a 1:1 chat — it must contain exactly one other member. Supply `groupName` to
create a group, which may contain many members. Creating a 1:1 chat that already exists is
idempotent: you get the existing `chatId` back rather than a duplicate.

### Messages

`GET /api/chats/{chatId}/messages?limit=30&cursor=<opaque>` walks backwards through history, newest
first. Pass the `nextCursor` from the previous response back as `cursor`. Omit it for the newest
page. `hasMore` tells you whether to keep going. Never build the cursor yourself, and never assume
its format — it is opaque base64.

Each message carries a `receipts` summary. For **your own** messages it reports how many recipients
have received or read it; that is what drives the tick marks. For messages you received it is all
zeros, since you already know the state.

```json
{ "id": "...", "chatId": "...", "senderId": "...", "clientMessageId": "...",
  "content": "decrypted text", "type": "TEXT", "replyToId": null,
  "createdAt": "2026-10-03T15:26:48.604829Z",
  "receipts": { "total": 2, "delivered": 2, "read": 1 } }
```

`type` is one of `TEXT`, `IMAGE`, `VIDEO`, `AUDIO`. `content` is decrypted server-side — send plain
text. Messages you are not a member of return 403 `NOT_CHAT_PARTICIPANT`.

## WebSocket

Endpoint `ws://localhost:8080/ws`, STOMP subprotocol `v12.stomp`.

A frame is `command EOL (header EOL)* EOL body NUL`. If you include `content-length`, the `NUL` must
come immediately after the body with no trailing newline.

### Connect

Send the token in the CONNECT frame's `Authorization` header:

```
CONNECT
accept-version:1.2
host:localhost
heart-beat:0,0
Authorization:Bearer <accessToken>

<NUL>
```

`heart-beat` is required by STOMP 1.2. A valid token is answered with `CONNECTED`; a missing,
malformed or expired token is answered with `ERROR`. Rejection is silent otherwise, so treat
`ERROR` as "reconnect and re-authenticate".

### Subscribe

Per-user channels, using **your own** user id:

| Destination | Payload |
| --- | --- |
| `/topic/user.{userId}/messages` | a new message |
| `/topic/user.{userId}/receipts` | delivery/read status for a message you sent |
| `/topic/user.{userId}/typing` | `{chatId, userId, at}` |
| `/topic/user.{userId}/presence` | your own presence change |

Per-chat channels:

| Destination | Payload |
| --- | --- |
| `/topic/chat.{chatId}/read` | a receipt from anyone in the chat |
| `/topic/chat.{chatId}/online-users` | a member's presence change |

Subscribing to `/topic/user.{someoneElse}/...` is rejected. You only ever receive your own per-user
traffic.

### Send

`SEND` to `/app/chat.send`:

```json
{ "chatId": "...", "clientMessageId": "local-uuid", "content": "hello",
  "type": "TEXT", "replyToId": null }
```

`clientMessageId` is required and must be unique per message. Generate it once on the client and
reuse it for retries — resending the same `clientMessageId` is idempotent and will not create a
duplicate, so you can safely retry on reconnect. The server echoes the saved message back to you on
`/topic/user.{yourId}/messages` alongside every other recipient, so you can confirm from the same
channel you send to.

### Receipts

`SEND` to `/app/chat.receipt`:

```json
{ "messageId": "...", "status": "DELIVERED", "occurredAt": null }
```

Send `DELIVERED` when a message reaches the device and `READ` when the chat is open. The original
sender receives it on `/topic/user.{senderId}/receipts`. Status only moves forward — a stale
`DELIVERED` replayed after a `READ` is ignored, so ticks never regress.

### Typing

`SEND` to `/app/chat.typing` with `{messageId, status, occurredAt}`. Other members receive
`{chatId, userId, at}`. Include any `messageId` the recipient can see in that chat; it is used only
to resolve the chat.

## Presence

`isOnline` in user payloads and chat participants reflects whether the user has at least one live
WebSocket session. On disconnect, `lastSeen` is stamped. Presence is best-effort: if the server
dies while a user is connected, `isOnline` stays true until their next connection replaces it.

## Encryption

Message content is encrypted at rest with AES-256-GCM and decrypted on read, so `content` in
responses is already plaintext. This is transport-independent — put TLS in front of this service for
anything beyond local development. `User.publicKey` exists for end-to-end encryption but is not yet
used; messages are currently readable by the server.