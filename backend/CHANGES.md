# Backend changes — what moved, and what it means for the frontend

Everything changed since `d178db0` (the last commit on `main`) is listed here. The authoritative
contract is [`API.md`](API.md); this document exists so nothing gets missed when building against it.

Read the **Breaking** section first — three things will break a client written against the previous
contract.

---

## Breaking changes

### 1. The socket broadcast is now `MessageResponse`

**Before.** `chat.send` broadcast `SendMessageResponse`:

```json
{ "messageId": "...", "chatId": "...", "clientMessageId": "...", "type": "TEXT",
  "createdAt": "...", "senderRole": "ADMIN" }
```

**After.** It broadcasts the same record as message history, and `SendMessageResponse` no longer
exists:

```json
{ "id": "...", "chatId": "...", "senderId": "...", "clientMessageId": "...",
  "content": "hello", "type": "TEXT", "replyToId": null, "createdAt": "...",
  "receipts": { "total": 1, "delivered": 0, "read": 0 } }
```

**Why.** The old payload had **no `content` and no `senderId`**, so a recipient could not render an
incoming message at all — the only way to see the text was to refetch history on every message. The
`messageId` vs `id` split also forced every client to special-case the socket when reconciling an
optimistic bubble.

**Frontend impact.**

- One message type for both transports. No second interface, no reconciliation shim.
- Reconcile an optimistic bubble by matching `clientMessageId`; the echoed frame has the real `id`
  and the real `content`, so no follow-up fetch is needed.
- `senderRole` is gone. Derive it by looking up `senderId` in
  `ChatSummaryResponse.participants[].role` — the client already has that data.
- `receipts` on the broadcast is the **sender's** view: `total` is the recipient count, `delivered`
  and `read` are `0` for a message that was just created. Recipients receive the same payload and
  should ignore it, exactly as with history.

### 2. Rejected frames now report, on a new channel

**Before.** An invalid or unauthorised inbound frame was logged server-side and **silently dropped**.
The client got no response of any kind, and could only detect failure by timing out.

**After.** `/topic/user.{yourId}.errors`:

```json
{ "code": "VALIDATION_ERROR", "message": "Request validation failed",
  "destination": "/app/chat.send", "receipt": "r-7" }
```

**Why.** A silently dropped `SEND` is unusable: you cannot mark a bubble as failed, and you cannot
tell a rejected send from a slow one. `code` uses the same `ErrorCode` names as REST, so one branch
handles both transports. **The connection stays open** — the Spring fallback would have closed the
socket with `PROTOCOL_ERROR`, turning one bad frame into a dropped connection.

**Frontend impact.**

- Subscribe to `/topic/user.{yourId}.errors` on connect.
- Send every frame with a STOMP `receipt` header. It comes back on the error, which is how you
  attribute a failure to one in-flight message rather than to the connection as a whole.
- On an error, look up the pending send by `receipt` and mark it failed.
- Absence of an error means success — the server never emits one for a frame that worked.

### 3. `POST /api/chats/{id}/read` now emits an event

**Before.** It cleared the server-side unread count and told nobody. Other tabs and devices kept
showing a stale badge until a manual refetch.

**After.** It also broadcasts `/topic/user.{yourId}.read-state`:

```json
{ "chatId": "...", "userId": "...", "lastReadAt": "2026-10-03T19:45:16.550Z" }
```

**Why.** It goes to **your** topic, not the chat's: an unread count is a per-user projection, so
telling other members would inform them of a number they cannot use. What other members have read is
already carried by the per-message receipts.

**Frontend impact.** Subscribe and zero the badge for that chat. `userId` always equals your own id —
the field is there so a client can sanity-check the routing.

---

## One user shape instead of three

`SyncedContact` is **deleted**, and `AuthResponse.user` no longer has its own nested type. Every
user-shaped response — login, registration, `/api/users/me`, `/api/users/{userId}`, and each element
of `/api/users/sync` — is now the same record:

```json
{ "id": "...", "username": "alice", "phoneNumber": "+14155550100",
  "profilePictureUrl": null, "about": null, "online": false, "lastSeen": null }
```

The auth response **gained** `online` and `lastSeen`. They are `false` / `null` at login and populate
once the socket is open, which is accurate rather than missing.

**Frontend impact.** Delete the duplicate interfaces; keep one. This was pure deduplication — the
field sets were already identical apart from the two presence fields the auth response lacked.

---

## New: media via presigned S3 URLs

Three endpoints, and one new field on the send payload. Attachments were previously typed and stored
but **impossible to upload**, so this is all net-new.

| Method | Path | Body | Returns |
| --- | --- | --- | --- |
| POST | `/api/media/upload-url` | `{contentType, sizeBytes}` | `{objectKey, uploadUrl, headers, expiresInSeconds, expiresAt}` |
| POST | `/api/media/complete` | `{objectKey}` | `{verified, sizeBytes, contentType, reason}` |
| POST | `/api/media/download-url` | `{messageId}` | `{downloadUrl, expiresInSeconds, expiresAt}` |

Flow: presign → `PUT` the bytes to `uploadUrl` with exactly the returned `headers` → `complete` →
send the message with `mediaKey`. To render an attachment, request a download URL **by message id**.

**Why not by key.** Accepting a bare `objectKey` would mean anyone who ever learned one could read
that object directly, so download is authorised by chat membership and the key is never an input.
The same reasoning puts an ownership check on upload: a `mediaKey` must sit under the sender's own
`media/{userId}/` prefix, so nobody can attach a file they have no access to and have the whole chat
download it.

**`complete` is not optional.** A presigned `PUT` cannot carry a byte-range condition, so the size
check in step 1 is advisory only. `complete` re-reads the stored object and **deletes it** if the
real size or type contradicts the declaration. Check `verified` before sending the message.

**Frontend impact.**

- New `SendMessagePayload` fields: `mediaKey` (nullable, ≤512 chars). `content` is **no longer
  `@NotBlank`** — it is the caption and may be empty for an attachment.
- Invariants enforced server-side: `TEXT` requires non-empty `content` and rejects a `mediaKey`;
  `IMAGE`/`VIDEO`/`AUDIO` require a `mediaKey`. Both come back as `VALIDATION_ERROR` on `.errors`.
- A `400 VALIDATION_ERROR` from `chat.send` can now mean either condition — branch on `code`, not
  message text.
- Rendering an image costs one round trip per bubble for its download URL. URLs are not cache-friendly
  by design (15 min). Cache them client-side, or ask for a CDN in front of the bucket.

### Media is off by default

`MEDIA_BUCKET` ships empty. Until it is set the three endpoints return
`503 MEDIA_NOT_CONFIGURED` and message sending still works — a client can ship the text experience
first and treat media as a capability flag.

`MEDIA_NOT_CONFIGURED` is the only new `ErrorCode` (`→ 503`). Every other code and its HTTP status is
unchanged.

---

## Corrected, with no code change

Two things I previously flagged as bugs turned out to be working as intended. **No client change is
needed for either**, but both are worth knowing because they contradict what the docs used to imply:

- **`limit` on message history caps at 100 and was always correct.** It is `Math.clamp`, which clamps
  the value and only throws when `min > max`. `limit=1000` silently becomes `100`; `limit=0` becomes
  `1`. Nothing is rejected.
- **`@Valid @Payload` on `chat.send` was always enforced**, by `PayloadMethodArgumentResolver` —
  independently of whether the controller carries `@Validated`. This is why a bad `type` surfaces as a
  validation failure rather than an enum crash.

## Also changed, no frontend impact

- New Flyway migration `V3__message_media_key.sql` adds `messages.media_key`. Because
  `ddl-auto=validate`, schema and entities are checked against each other at startup.
- AWS SDK v2 `2.55.11` added and **pinned** — the Spring Boot BOM does not manage it.
- Dead code removed: `MessageResponse.aggregateStatus` and the duplicate `SyncedContact`.
- Internal signatures only: `Message.create`, `MessageResponse.from`, `MessageService.save` (now
  returns `MessageResponse`), `MessageService.markChatRead` (now returns the instant it applied).
- `ChatController` gained a `RealtimeBroadcaster` dependency to emit read-state.
- Local secrets load from `backend/.env` via `spring.config.import`, and `MediaConfig` reads
  credentials from Spring's `Environment` — the AWS SDK's own chain cannot see an imported value.
  Operational only; nothing observable over the API.
- `.gitignore` now covers `.env` and `*.csv`. This closes a real hole: the access-key CSV was one
  `git add .` from being committed.

---

## Quick reference: destinations after this change

| Destination | New? | Payload |
| --- | --- | --- |
| `/topic/user.{id}.messages` | payload changed | `MessageResponse` — now with `content` and `senderId` |
| `/topic/user.{id}.errors` | **new** | `{code, message, destination, receipt}` |
| `/topic/user.{id}.read-state` | **new** | `{chatId, userId, lastReadAt}` |
| `/topic/user.{id}.receipts` | unchanged | `{messageId, status, chatMemberIds, occurredAt}` |
| `/topic/user.{id}.typing` | unchanged | `{chatId, userId, at}` |
| `/topic/user.{id}.presence` | unchanged | `{userId, username, online, lastSeen, chatIds}` |
| `/topic/chat.{id}.read` | unchanged | a receipt from any member |
| `/topic/chat.{id}.online-users` | unchanged | a member's presence change |

## Verification

| Suite | Count | Covers |
| --- | --- | --- |
| `RealtimeContractTests` | 7 | live WebSocket clients: renderable broadcast, error channel + socket survival, read-state, idempotent replay, media key ownership, text invariants, uniform user shape |
| `MediaTests` | 4 | presigning offline against dummy credentials, allowlist and size rejection, foreign key rejection |
| `BackendApplicationTests` | 1 | context load |
| `MediaS3RoundTripTests` | 1 (opt-in) | real S3: presign → PUT → verify → send → **another member downloads** → non-member refused |

All run against live Postgres, Redis and RabbitMQ — no mocks, because the parts that break silently
are broker fan-out, subscription authorisation and Flyway/entity agreement.

```bash
./mvnw test                                        # 12 tests, S3 test skipped
RUN_S3_TESTS=true ./mvnw test -Dtest=MediaS3RoundTripTests
```