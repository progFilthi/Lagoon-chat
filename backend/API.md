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

Send the token on every other request:

```
Authorization: Bearer <accessToken>
```

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
            "profilePictureUrl": null, "about": null,
            "online": false, "lastSeen": null } }
```

Tokens last 30 days. There is no refresh token and no logout — discard the token client-side.
`online` and `lastSeen` are present but empty at login; they are populated once the client holds a
socket.

### Users

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/users/sync` | resolve local contacts to registered users |
| GET | `/api/users/me` | current user |
| GET | `/api/users/{userId}` | any user's profile |

Every user-shaped response — auth, `/me`, `/{userId}`, and each element of `/sync` — is the same
record, so one client type covers all of them:

```json
{ "id": "...", "username": "alice", "phoneNumber": "+14155550100",
  "profilePictureUrl": null, "about": null, "online": true,
  "lastSeen": "2026-10-03T16:28:48.654937Z" }
```

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

`type` is `TEXT`, `IMAGE`, `VIDEO` or `AUDIO`. **`MessageResponse` is the only message shape in this
API** — the socket broadcast on `chat.send` is the same record, so a client has one type and no
reconciliation step.

`receipts` is only meaningful on **your own** messages — that is what drives the tick marks. For
messages you received it is all zeros, because you already know the state. `delivered` counts every
receipt at `DELIVERED` **or beyond**, so it includes the ones already read: everyone has received it
when `delivered == total`, and everyone has read it when `read == total`. `content` is decrypted
server-side, so send and expect plain text; for an attachment it is the caption, which may be empty.

Non-members get 403 `NOT_CHAT_PARTICIPANT`.

## Media

Attachments live in a private S3 bucket and never pass through this service. The server signs a
short-lived URL, the browser transfers the bytes directly, and only the object key is stored on the
message. No AWS credential ever reaches a browser.

Three calls, in order:

| Method | Path | Body | Returns |
| --- | --- | --- | --- |
| POST | `/api/media/upload-url` | `{contentType, sizeBytes}` | `{objectKey, uploadUrl, headers, expiresInSeconds, expiresAt}` |
| POST | `/api/media/complete` | `{objectKey}` | `{verified, sizeBytes, contentType, reason}` |
| POST | `/api/media/download-url` | `{messageId}` | `{downloadUrl, expiresInSeconds, expiresAt}` |

1. Ask for an upload URL. `contentType` must be in the configured allowlist and `sizeBytes` must be
   positive and within `MEDIA_MAX_BYTES` (default 25 MiB) or you get 400.
2. `PUT` the bytes to `uploadUrl` with **exactly** the headers in `headers` and nothing else. The
   content type is signed into the request, so S3 rejects the upload if the header does not match.
3. `POST /api/media/complete`. A presigned `PUT` cannot carry a byte-range condition, so the size
   check in step 1 is only advisory; `complete` re-reads the stored object and **deletes it** if the
   real size or type contradicts what you declared. Check `verified` before sending the message.
4. Send the message with `type` and `mediaKey` set to the `objectKey` from step 1.

To render an attachment, request a download URL **by message**, never by key:

```json
{ "downloadUrl": "https://...?X-Amz-Signature=...", "expiresInSeconds": 900, "expiresAt": "..." }
```

The server checks that you are a member of the chat that message belongs to. Accepting a bare
`objectKey` would mean anyone who ever learned a key could read that object directly, so the key is
never an input. A non-member gets 403; a text message gets 400.

Upload URLs last 5 minutes and download URLs 15 (`MEDIA_UPLOAD_URL_TTL`, `MEDIA_DOWNLOAD_URL_TTL`).
A signed URL is a bearer token for one object until it expires, and the download TTL is not
cache-friendly by design — put a CDN in front of the bucket if that becomes a problem, or raise it
knowing you have widened the window.

### Configuration

Media is **off until `MEDIA_BUCKET` is set**, and then the endpoints return 503
`MEDIA_NOT_CONFIGURED` rather than failing startup, so frontend work is never blocked on AWS. The
backend still starts without any AWS configuration at all.

| Variable | Default | Purpose |
| --- | --- | --- |
| `MEDIA_BUCKET` | *(empty — media disabled)* | target bucket |
| `AWS_REGION` | `us-east-1` | bucket region |
| `MEDIA_MAX_BYTES` | `26214400` | 25 MiB |
| `MEDIA_UPLOAD_URL_TTL` | `5m` | upload URL lifetime |
| `MEDIA_DOWNLOAD_URL_TTL` | `15m` | download URL lifetime |
| `MEDIA_ALLOWED_CONTENT_TYPES` | see `application.yaml` | `type=ext` pairs; extensions are pinned here so a client cannot choose the stored suffix |

Credentials come from the Spring `Environment` when `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`
are both set, and otherwise from the AWS SDK default provider chain (`AWS_PROFILE`, an instance/task
role, web identity). This indirection is deliberate: the SDK's own chain reads system properties and OS
environment variables only, so a value loaded from `.env` through `spring.config.import` is invisible
to it. Never put credentials in `application.yaml` and never forward them to a browser.

For local development, give the backend an IAM user scoped to the one bucket — `PutObject`,
`GetObject` and `DeleteObject` on `arn:aws:s3:::<bucket>/*` is sufficient, and `ListBucket` on
`arn:aws:s3:::<bucket>` is only needed for multipart uploads. Pair it with a bucket policy denying
non-TLS requests. In production prefer an IAM role over a static user.

`AWS_REGION` must match the bucket's region. A mismatch does not fail at startup; it fails on the
first upload with `AuthorizationQueryParametersError`, which names the correct region. A `403` on the
PUT instead means the IAM policy is attached to a different bucket.

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
| `/topic/user.{userId}.read-state` | `{chatId, userId, lastReadAt}` — your own read watermark moved |
| `/topic/user.{userId}.errors` | `{code, message, destination, receipt}` — a frame you sent was rejected |

Per-chat channels:

| Destination | Payload |
| --- | --- |
| `/topic/chat.{chatId}.read` | a receipt from any member |
| `/topic/chat.{chatId}.online-users` | a member's presence change |

`read-state` is on **your** topic, not the chat's: an unread count is a per-user projection, so
telling the other members would inform them of a number they cannot use. Subscribe to it to clear
the badge in your other tabs and devices. What other members have read is carried by the per-message
receipts instead.

### Errors

A rejected inbound frame is reported on your own `errors` topic rather than as a STOMP `ERROR`
frame, and **the connection stays open**:

```json
{ "code": "VALIDATION_ERROR", "message": "...", "destination": "/app/chat.send", "receipt": "r-7" }
```

`code` uses the same `ErrorCode` names as REST, so a client branches on one vocabulary across both
transports. Set a `receipt` header on every `SEND` and it comes back here, which is how you tie a
failure to one specific in-flight message instead of to the connection as a whole. Nothing is ever
returned for a frame that succeeded, so an absent error is not an error.

Send with a receipt header:

```
SEND
destination:/app/chat.send
receipt:r-7
content-type:application/json
content-length:131

{"chatId":"...","clientMessageId":"local-uuid","content":"hello","type":"TEXT","replyToId":null}NUL
```

Separators are **dots**, not slashes — they map to RabbitMQ topic routing keys, which reject `/`.
Subscribing to `/topic/user.{someoneElse}...` is rejected, so you only ever receive your own
per-user traffic.

### Send

`SEND` to `/app/chat.send`:

```json
{ "chatId": "...", "clientMessageId": "local-uuid", "content": "hello",
  "type": "TEXT", "replyToId": null, "mediaKey": null }
```

`clientMessageId` is required and unique per message. Generate it once on the client and reuse it
across retries — resending the same value is **idempotent** and will not create a duplicate, so you
can retry safely on reconnect. The saved message is echoed back to you on
`/topic/user.{yourId}.messages` alongside every other recipient, so confirm from the same channel
you send on. Because the echo is a full `MessageResponse` carrying `content`, matching on
`clientMessageId` is enough to replace an optimistic bubble — no second fetch.

`content` is required for `TEXT` and must be empty or a caption otherwise. `mediaKey` comes from
[`POST /api/media/upload-url`](#media) and is required for `IMAGE`/`VIDEO`/`AUDIO` and rejected on
`TEXT`. It must be a key issued to the sender: the server rejects a key under anyone else's prefix,
so a user cannot attach a file they have no access to and have the whole chat download it.

### Receipts

`SEND` to `/app/chat.receipt`:

```json
{ "messageId": "...", "status": "DELIVERED", "occurredAt": null }
```

Send `DELIVERED` when a message reaches the device and `READ` when the chat is open. The original
sender receives it on `/topic/user.{senderId}.receipts`. Status only ever moves forward — a stale
`DELIVERED` replayed after a `READ` is ignored, so ticks never regress, and **a receipt that changes
nothing produces no frame at all**. Absence therefore means "already at that level", not "dropped".

`occurredAt` is echoed back as sent rather than normalised to server time.

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