# WhatsApp Clone — Backend

Spring Boot 4.1.1 / Java 25 backend. PostgreSQL for durable state, Redis for presence,
RabbitMQ for real-time fan-out. The HTTP and WebSocket contract the frontend codes against is in
[`API.md`](API.md).

## Running it

```bash
docker compose up -d          # postgres, redis, rabbitmq
cd backend
./mvnw spring-boot:run        # http://localhost:8080
```

RabbitMQ's management UI is at http://localhost:15672 (`whatsapp` / `whatsapp_secret`) — useful for
confirming subscriptions and connections during debugging.

```bash
./mvnw test                   # context load test
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
| `messages` | unique `(sender_id, client_message_id)`, index `(chat_id, created_at, id)` |
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

## Known limitations

Deliberately out of scope, listed so they are not mistaken for oversights:

- `isOnline` is a projection of live sessions. If the process dies while a user is connected, the
  flag stays `true` until that user's next connection corrects it. There is no reconciliation job.
- Chat topic subscriptions are not membership-checked, so a client may subscribe to
  `/topic/chat.{id}.read` for a chat it is not in. Payloads there carry only a message id and
  status, but it is still information disclosure and should be closed if this goes further.
- Media messages are typed and stored but there is no upload endpoint yet.
- No refresh tokens, no logout, no rate limiting.
- `reply_to_id` is stored and returned but the UI has nothing to do with it yet.