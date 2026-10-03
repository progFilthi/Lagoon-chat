# WhatsApp Clone — Backend

Spring Boot 4.1.1 / Java 25 backend. PostgreSQL for durable state, Redis for presence,
RabbitMQ for real-time fan-out. The HTTP and WebSocket contract the frontend codes against is in
[`API.md`](API.md). If you are picking this up after the media and contract work, start with
[`CHANGES.md`](CHANGES.md) — it lists every contract change and what a client has to do about it.

## Running it

```bash
docker compose up -d          # postgres, redis, rabbitmq
cd backend
./mvnw spring-boot:run        # http://localhost:8080
```

RabbitMQ's management UI is at http://localhost:15672 (`whatsapp` / `whatsapp_secret`) — useful for
confirming subscriptions and connections during debugging.

```bash
./mvnw test                   # integration suite; needs the containers above
./mvnw -o compile             # offline build
```

### Configuration

Everything is environment-overridable; the defaults in `application.yaml` match `compose.yaml`.

| Variable | Default |
| --- | --- |
| `POSTGRES_HOST` / `_PORT` / `_DB` / `_USER` / `_PASSWORD` | `localhost` / `5432` / `whatsapp_clone` / `whatsapp_user` / `whatsapp_secret` |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |
| `RABBITMQ_HOST` / `RABBITMQ_STOMP_PORT` | `localhost` / `61613` |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` / `RABBITMQ_VHOST` | `whatsapp` / `whatsapp_secret` / `/` |
| `JWT_SECRET` | dev placeholder — **override in production** |
| `MESSAGE_ENCRYPTION_KEY` | dev placeholder — **override in production** |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` |
| `SERVER_PORT` | `8080` |
| `MEDIA_BUCKET` | *(empty — media endpoints return 503)* |
| `AWS_REGION` | `us-east-1` |
| `MEDIA_MAX_BYTES` | `26214400` (25 MiB) |

AWS credentials are **not** configuration here — they resolve from the SDK's default provider chain
(`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_PROFILE`, or an instance/task role), and must
never be forwarded to a browser. See [`API.md`](API.md#media) for the upload flow and the IAM policy
the backend actually needs.

### Local secrets

Put credentials in `backend/.env`, which is git-ignored — along with `*.csv`, so the access-key CSV
AWS hands you cannot be committed by accident:

```
AWS_ACCESS_KEY_ID=AKIA...
AWS_SECRET_ACCESS_KEY=...
MEDIA_BUCKET=whatsapp-media
AWS_REGION=ap-southeast-1
```

It is loaded by `spring.config.import: "optional:file:.env[.properties]"` in `application.yaml`. The
`[.properties]` hint is mandatory — without it Spring parses the file as YAML and fails on `KEY=value`
lines — and `optional:` is what lets startup proceed when the file is absent, which is how CI and
production work, taking the same variables from the real environment instead.

**The AWS SDK cannot see anything in that file on its own.** Its default credentials chain reads system
properties and OS environment variables, never Spring's `Environment`, so credentials imported this
way are visible to Spring and invisible to the SDK — and the first presign fails with "Unable to load
credentials from any of the providers in the chain". `MediaConfig` therefore reads `AWS_ACCESS_KEY_ID`
/ `AWS_SECRET_ACCESS_KEY` from the `Environment` and builds a `StaticCredentialsProvider` when both are
present, falling back to the default chain when they are not. That fallback is what keeps instance
roles, web identity and shared profiles working in production.

If you run from IntelliJ rather than `./mvnw`, confirm the run configuration's working directory is the
`backend` module, since `.env` is resolved relative to it. Pasting the variables into the run
configuration's environment field works too, and takes precedence over the imported file.

### Verifying an upload end to end

```bash
# 1. presign
curl -s -X POST localhost:8080/api/media/upload-url \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"contentType":"image/png","sizeBytes":8}'
# 2. PUT the bytes exactly as instructed
curl -X PUT "$UPLOAD_URL" -H 'Content-Type: image/png' --data-binary 'PNGDATA!'
# 3. confirm
curl -s -X POST localhost:8080/api/media/complete \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"objectKey\":\"$KEY\"}"
```

Two failures worth recognising, because both look like code bugs and are not:

- **`AuthorizationQueryParametersError: the region 'x' is wrong; expecting 'y'`** — the bucket is real
  and the signature is fine, but `AWS_REGION` does not match the bucket's region. S3 names the right
  one in the error.
- **`403` on the PUT** — the credentials are fine but the IAM policy is not attached to *this* bucket.
  Check that the policy's `Resource` ARN names the bucket in `MEDIA_BUCKET`.

To find which buckets a key can see, `ListBuckets` is fastest; when that is denied, probe candidates
with `head-bucket`, which answers 403 for a bucket that exists but grants no `ListBucket` and 404 for
one that does not exist.

## Tests

`./mvnw test` runs the whole suite against the live Postgres, Redis and RabbitMQ from
`compose.yaml` — there are no mocks in it, because the parts most likely to break silently
(STOMP fan-out through the broker, subscription authorisation, Flyway agreeing with the entities)
are exactly the parts a mock would hide.

`RealtimeContractTests` drives real WebSocket clients: it asserts a recipient can render a message
from the broadcast alone, that a rejected frame reports a code and leaves the socket open, that
marking a chat read reaches the user's other sessions, and that replaying a `clientMessageId` does
not create a duplicate. `MediaTests` verifies presigned URLs offline, since signing is local
computation and needs no S3.

`MediaS3RoundTripTests` is opt-in because it writes to a real bucket:

```bash
RUN_S3_TESTS=true ./mvnw test -Dtest=MediaS3RoundTripTests
```

It is the only test that covers what the offline suite cannot: that the bucket policy actually permits
what a presigned URL asks for, and that a chat member who did *not* upload can still download. It
reads `MEDIA_BUCKET` and the credentials from `backend/.env`, and deletes the object it creates,
including on failure.

If Docker was shut down uncleanly, Postgres can refuse to start on a stale `postmaster.pid`. Remove
it from the `postgres_data` volume and recreate the container.

## Layout

```
com.whatsappclone.backend
├── common
│   ├── api             ApiResponse, ApiError, PageResponse
│   ├── crypto          SHA-256 hashing, AES-GCM content encryption
│   ├── exception       ErrorCode, AppException, GlobalExceptionHandler
│   └── realtime        RealtimeBroadcaster
├── auth                JwtService, JWT filter, AuthController, AuthService
├── user                User, UserRepository, UserController, UserService
├── chat                Chat, ChatParticipant, ChatController, ChatService
├── message             Message, MessageReceipt, MessageService, STOMP controller
├── media               S3 presigned upload/download, MediaService
├── presence            PresenceService (Redis), PresenceEventListener
├── security            @CurrentUser resolver, STOMP auth interceptor
└── config              Security, WebSocket, MVC
```

Controllers stay thin; every rule lives in a `@Service`. DTOs are records. Entities are package-private
mutating and exposed through factory methods so invariants cannot be bypassed.

## Data model

| Table | Notes |
| --- | --- |
| `users` | UUID pk, unique `phone_number` and `phone_number_hash`, BCrypt `password_hash` |
| `chats` | `is_group`, `last_message_at` indexed for the chat-list ordering |
| `chat_participants` | unique `(chat_id, user_id)`, `role`, `last_read_at` |
| `messages` | unique `(sender_id, client_message_id)`, index `(chat_id, created_at, id)`, nullable `media_key` |
| `message_receipts` | unique `(message_id, user_id)`, index `(user_id, status)` |

Schema lives in `db/migration` and is validated against the entities with
`spring.jpa.hibernate.ddl-auto=validate`, so a drift between code and database fails startup rather
than surfacing at runtime.

Three decisions worth explaining, because each one replaced something that looked correct:

**Delivery status lives on `message_receipts`, not `messages`.** Status is a property of a
(message, recipient) pair, not of a message. In a group, one member can have read a message while
another has not even received it, so any single column is wrong the moment it is written. One row
per recipient also makes unread counts a single indexed aggregate —
`count where user_id = me and status <> 'READ'` — instead of scanning every message in every chat on
each `/api/chats` call. Receipt status is monotonic: `markStatus` only accepts `DELIVERED` from
`SENT` and `READ` from anything not already `READ`, so a stale `DELIVERED` replayed by a
reconnecting client cannot move ticks backwards.

**Keyset pagination, split into two statements.** History pages on `(created_at, id)` with an opaque
cursor rather than an offset, so deep pages stay fast and inserts cannot shift or duplicate rows
underneath you. There are deliberately two queries — a first page and a cursor page — rather than one
with a `:param is null` guard: Postgres cannot infer the type of an untyped null bind parameter
(`could not determine data type of parameter $2`), and the null-guarded form also degrades the plan
away from the `(chat_id, created_at, id)` index.

**Entities are saved via the value `save` returns.** Each entity gets its UUID inside a factory
method, which makes Spring Data's `isNew` report `false` and route the entity through `merge()` rather
than `persist()`. Auditing callbacks then populate `createdAt`/`updatedAt` on the *managed copy*, so
the instance the caller still holds reads back `null`. Using the returned instance avoids that whole
class of bug.

## Real-time design

Two constraints shaped this, both found by testing rather than by reading docs.

**`convertAndSendToUser` silently does nothing here.** It resolves target sessions through
`SimpUserRegistry`, keyed on the `Principal` attached to the `WebSocketSession`. STOMP CONNECT frames
arrive with **immutable** headers, so the principal cannot be written back onto them, the session
never gets one, and the registry resolves zero sessions. The send is a no-op with no error — messages
simply vanish. Instead of fighting it, fan-out uses explicit broker destinations
(`/topic/user.{id}.messages`) and `StompAuthChannelInterceptor` authorises `SUBSCRIBE`, so a user
physically cannot listen to someone else's queue. This is why per-user topics rather than
`/user/...` user-destinations.

**RabbitMQ replaces the in-memory simple broker.** With `enableSimpleBroker` each instance only
delivers to sockets attached to itself, so a user connected to instance B never sees a message
published on instance A — and nothing fails to tell you. The relay (`enableStompBrokerRelay`) makes
every instance a subscriber of the same exchanges, so horizontal scaling is a matter of adding
instances behind a load balancer with no code change. The relay speaks STOMP to the broker's stomp
plugin on **61613**, not AMQP to 5672, which is why that plugin is enabled in `compose.yaml`.

Two consequences of talking to a real topic exchange:

- Destinations are **dot** separated, not slash separated. RabbitMQ validates each word of a routing
  key and rejects any containing `/`.
- A message published to a per-user topic reaches every subscriber of that key across all instances,
  which is what we want for fan-out but means there is no per-session single delivery. Presence
  counters in Redis, not broker state, decide who is connected.

**Authentication** happens at CONNECT, before the session exists. The principal is stored on the
WebSocket session attributes map rather than in message headers, because inbound frames are
immutable — `StompSessionRegistry` bridges it to the connect/disconnect lifecycle events, whose
accessors do not carry session attributes.

## Rejected frames are reported, not swallowed

An inbound frame that fails validation used to vanish: the handler logged a warning and returned,
and the client saw nothing at all. The obvious fix — delete the `@MessageExceptionHandler` and let
the transport raise a STOMP `ERROR` — makes it strictly worse, for two reasons found in
`spring-messaging` 7.0.9's source rather than in the docs:

- `AbstractMethodMessageHandler.processHandlerMethodException` **swallows** an unhandled exception.
  With no handler method it logs and returns without rethrowing, so nothing reaches the transport.
  Removing the handler converts a silent failure into a differently silent failure.
- `StompSubProtocolHandler.sendErrorMessage` — the fallback used when no `StompSubProtocolErrorHandler`
  is registered — emits its ERROR frame and then **closes the socket** with `PROTOCOL_ERROR`. One
  malformed `SEND` would drop the connection.

So the handler stays and reports explicitly, on `/topic/user.{id}.errors` with the same `ErrorCode`
vocabulary as REST plus the client's `receipt` header echoed back. Being an ordinary topic rather than
an `ERROR` frame also fits the constraint above: this codebase cannot use user destinations, so a
per-user topic is the only way to reach one specific client. The socket stays open, which is the part
that matters for a chat client mid-send.

Mapping the failure to a code walks the **cause chain**, because the exception carrying the useful
signal is rarely the outermost one: a bad `type` surfaces as a
`MethodArgumentNotValidException` from argument resolution, a bad payload as an `AppException` from
the service, and an unparseable one from a converter several layers down. `INTERNAL_ERROR` reports a
generic message rather than leaking internals, matching `GlobalExceptionHandler`.

## One message shape

The `chat.send` broadcast used to be a second, narrower record than message history: it had
`messageId` instead of `id`, and it carried **no `content` and no `senderId`**. A recipient therefore
could not render an incoming message at all — the only way to see the text was to refetch history on
every single message — and the `id`/`messageId` split meant a client reconciling an optimistic bubble
had to special-case the socket.

There is now one projection, `MessageService.project`, used by both history and the broadcast, so
there is one message shape in the API. `senderRole` was dropped from the broadcast as redundant: a
client derives it by matching `senderId` against the `role` it already has in
`ChatSummaryResponse.participants`. Receipt counts stay a sender-only projection, so the same payload
is correct for every recipient.

## Media

Attachments are stored in a private S3 bucket and never proxied through the service. The server
presigns a URL scoped to one key and one verb, the browser transfers the bytes directly, and only the
object key is persisted (`messages.media_key`). Presigning is local signature computation, so issuing
a URL makes no S3 request and the media tests run offline against dummy credentials.

Two decisions that are not obvious:

- **Download is authorised by `messageId`, never by key.** The service checks chat membership and
  then signs a URL for that message's object. Accepting a bare key would mean anyone who ever learned
  one could read that object directly. The same reasoning puts the upload-prefix check in
  `MessageService.resolveMediaKey`: keys are issued under `media/{userId}/`, so requiring that prefix
  stops a sender attaching a file they have no access to and having the whole chat download it.
- **A presigned `PUT` cannot enforce a byte range**, so the declared size is only advisory. That is
  why `POST /api/media/complete` exists: it re-reads the stored object and deletes it if the real size
  or type contradicts the declaration. A browser-native presigned `POST` policy would enforce the
  range outright, at the cost of a multipart form upload; worth revisiting if abuse becomes real.

Media is disabled until `MEDIA_BUCKET` is set, and the endpoints then return 503
`MEDIA_NOT_CONFIGURED` instead of failing startup, so frontend work is never blocked on AWS. The S3
and presigner beans are built unconditionally and resolve credentials lazily, which is what lets the
application boot with no AWS configuration at all.

## Security

- Stateless JWT (HS256) via a dedicated filter; `SecurityContext` is never persisted.
- Spring's default in-memory user is excluded (`UserDetailsServiceAutoConfiguration`). Without that,
  Boot creates a `user` account with a generated password even in a JWT-only app.
- `@CurrentUser` resolves the caller so controllers never touch `SecurityContext`.
- Every chat query and mutation checks membership; non-members get 403.
- STOMP `SUBSCRIBE` to another user's topic is refused.
- Passwords are BCrypt. Phone numbers are stored both in plaintext (display) and as a SHA-256 hash
  so contact sync can match without ever querying raw numbers.
- Message content is encrypted at rest with AES-256-GCM. This is **not** end-to-end encryption —
  the application decrypts on read. There is no E2E support and no `publicKey` column.
- Media objects live in a private bucket and are reachable only through short-lived signed URLs.
  Object keys are never accepted as an authorisation input, and an upload key must belong to the
  sender who attaches it.

## Known limitations

Deliberately out of scope, listed so they are not mistaken for oversights:

- `isOnline` is a projection of live sessions. If the process dies while a user is connected, the
  flag stays `true` until that user's next connection corrects it. There is no reconciliation job.
- Chat topic subscriptions are not membership-checked, so any authenticated user may subscribe to
  `/topic/chat.{id}.*`. Per-user topics **are** checked, which is why the read-state and error
  channels live there. Chat-topic payloads carry only a message id and status, but it is still
  information disclosure and should be closed if this goes further.
- Media messages are typed and stored, and uploads and downloads are implemented against a private S3
  bucket. Images still cost one round trip per bubble to obtain a download URL, since URLs are not
  cache-friendly by design; a CDN in front of the bucket removes that.
- No refresh tokens, no logout, no rate limiting.
- `reply_to_id` is stored and returned but the UI has nothing to do with it yet.