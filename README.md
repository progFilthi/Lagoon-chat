# WhatsApp Clone

A production-shaped, real-time messaging app built from scratch — Spring Boot and PostgreSQL on the
back, Next.js on the front, with direct-to-S3 media uploads and a STOMP-over-WebSocket realtime layer.

This is a teaching project. Every non-obvious decision is written down **with the reason it was made
and the alternative it replaced**, so the code reads as an argument rather than an answer key. If you
are here to learn how this kind of system is actually put together, start with
[`backend/readme.md`](backend/readme.md) — it is the long form, and it is the most valuable file in
the repository.

---

## Table of contents

- [What it does](#what-it-does)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Quick start](#quick-start)
- [Media uploads (S3)](#media-uploads-s3)
- [Running the tests](#running-the-tests)
- [Project layout](#project-layout)
- [The realtime design, in one page](#the-realtime-design-in-one-page)
- [Security model](#security-model)
- [Known limitations](#known-limitations)
- [Further reading](#further-reading)

---

## What it does

**Accounts** — phone-number registration and login. Passwords are BCrypt-hashed; phone numbers are
stored twice, once in plaintext for display and once as a SHA-256 hash so contact sync can match
numbers without ever querying them in the clear.

**Chats** — one-to-one chats, a searchable inbox ordered by recency, live typing indicators, online
presence, and last-seen. Every query and mutation is membership-checked.

**Messages** — real-time delivery over STOMP, optimistic rendering, delivery and read receipts with
monotonic ticks, read-state sync, and history pagination on a keyset cursor so deep pages stay fast
and concurrent inserts cannot shift rows underneath you.

**Media** — images, video and audio uploaded **straight from the browser to a private S3 bucket**. The
server only ever signs a URL scoped to one object and one verb; the bytes never touch the application
server. Downloads are authorised by message id, never by object key.

**Interface** — a chat UI built to a Paper design system: a 76px navigation rail, a 380px inbox, and a
thread with mirrored bubble tails, day separators and a composer whose geometry is verified against
the design in a real browser.

---

## Architecture

```
                      ┌───────────────────────────────────────────┐
   Browser  ────────▶ │  Next.js 16 (App Router, RSC)             │
      │               │  • httpOnly cookie session               │
      │   WebSocket   │  • /api/b/* reverse proxy to the backend  │
      │  (cookie auth)│  • TanStack Query cache + optimistic state│
      │               └───────┬───────────────────────┬───────────┘
                      REST    │                       │  STOMP over WS
                              ▼                       ▼
                      ┌───────────────────────────────────────────┐
                      │  Spring Boot 4 · Java 25                  │
                      │  • JWT auth (HS256)                       │
                      │  • every rule lives in a @Service         │
                      │  • thin controllers, record DTOs          │
                      └───┬──────────┬─────────────┬──────────┬────┘
                          │          │             │          │
                    ┌─────▼────┐ ┌───▼────┐  ┌─────▼─────┐ ┌──▼──────────┐
                    │PostgreSQL│ │ Redis  │  │ RabbitMQ  │ │ S3 (private)│
                    │durable   │ │presence│  │STOMP relay│ │ media bytes │
                    │state     │ │+session│  │fan-out    │ │ direct PUT  │
                    └──────────┘ └────────┘  └───────────┘ └─────────────┘
```

Three decisions in that diagram are load-bearing, and each replaced something that looked fine:

- **RabbitMQ instead of Spring's in-memory broker.** With `enableSimpleBroker`, a user connected to
  instance B never sees a message published on instance A, and nothing fails to tell you. Scaling out
  is then just adding instances behind a load balancer.
- **Explicit per-user topics instead of `/user/...` destinations.** STOMP CONNECT frames have
  *immutable* headers, so a principal can never be written back onto the session and
  `convertAndSendToUser` silently resolves zero sessions. Per-user topics also let `SUBSCRIBE` be
  authorised, so a user physically cannot listen to someone else's queue.
- **The browser talks to S3 directly.** Proxying 25 MiB attachments through the app server would spend
  the connection pool on bytes that never needed inspecting.

---

## Tech stack

| Layer | Choice | Why this one |
| --- | --- | --- |
| Language | Java 25 | Records for DTOs, sealed types, pattern matching |
| Framework | Spring Boot 4.1 | Starter security, WebSocket/STOMP, Data JPA, Flyway |
| Database | PostgreSQL 18 | Real constraints, `jsonb`, keyset-friendly indexes |
| Migrations | Flyway | Schema is versioned and `ddl-auto=validate` fails startup on drift |
| Cache / presence | Redis 8 | Session store, presence counters, TTLs |
| Message broker | RabbitMQ 4 (STOMP plugin) | Fan-out across instances without code changes |
| Auth | JWT HS256 + BCrypt | Stateless; the browser never holds the token |
| Media | AWS SDK v2 S3 | Presigned URLs are local computation — issuing one makes no S3 call |
| Frontend | Next.js 16 App Router | Server components for the shell, client for the chat surface |
| UI | React 19 | — |
| Data | TanStack Query v5 | Cache is the single source of truth the realtime layer patches |
| Realtime | `@stomp/stompjs` | Text frames, heartbeats, no proprietary protocol |
| Styling | Tailwind CSS v4 | `@theme` tokens generate the utilities, so tokens cannot drift |
| Runtime | Bun 1.3 | — |

---

## Quick start

**Prerequisites:** Docker, JDK 25, and either Bun or Node 20+.

**1. Start the infrastructure**

```bash
docker compose up -d
```

That brings up PostgreSQL (`5432`), Redis (`6379`) and RabbitMQ (`61613` STOMP, `15672` management UI
— `whatsapp` / `whatsapp_secret`). The defaults in `compose.yaml` are exactly the defaults in
`application.yaml`, so nothing needs configuring to run locally.

**2. Start the backend**

```bash
cd backend
./mvnw spring-boot:run        # http://localhost:8080
```

Flyway applies the migrations on first boot. Optional AWS credentials go in `backend/.env` — copy
`backend/.env.example` and fill it in, or skip it entirely; the app boots with no AWS configuration
and the media endpoints simply return `503 MEDIA_NOT_CONFIGURED`.

**3. Start the frontend**

```bash
cd frontend
bun install
bun run dev                   # http://localhost:3000
```

Register a first account and open the app in two browser profiles to see realtime delivery between
two sessions.

**4. (Optional) Import IntelliJ run configurations**

`.run/` and `backend/.run/` are checked in — open the project in IntelliJ and both the backend and
frontend will run with the right working directory and JVM flags. If you run from IntelliJ rather
than `./mvnw`, note that `backend/.env` is resolved relative to the **backend** module directory.

---

## Media uploads (S3)

Attachments never pass through the application server. The browser asks for a URL, transfers the bytes
to S3 itself, and only the object key is persisted.

```
POST /api/media/upload-url   { contentType, sizeBytes }   ->  { objectKey, uploadUrl, ... }
PUT  <uploadUrl>            the raw bytes, browser -> S3
POST /api/media/complete    { objectKey }                ->  verifies real size and type
POST /api/media/download-url{ messageId }                ->  short-lived signed GET
```

Two things to know before your first upload:

**The bucket needs a CORS rule.** A browser `PUT` is a cross-origin request, so without CORS the
preflight fails and the upload dies with a message that looks like a code bug:

```xml
<Error><Code>AccessForbidden</Code>
<Message>CORSResponse: CORS is not enabled for this bucket.</Message></Error>
```

```json
[
  {
    "AllowedHeaders": ["*"],
    "AllowedMethods": ["GET", "PUT", "POST", "DELETE", "HEAD"],
    "AllowedOrigins": ["http://localhost:3000"],
    "ExposeHeaders": ["ETag", "x-amz-request-id"],
    "MaxAgeSeconds": 3000
  }
]
```

Server-side tests pass without this, because the AWS SDK bypasses CORS entirely — it only shows up
in a browser.

**`POST /api/media/complete` is not optional.** A presigned `PUT` cannot enforce a byte range, so the
declared size is advisory; `complete` re-reads the stored object and deletes it if the real size or
type contradicts the declaration.

---

## Running the tests

```bash
cd backend
./mvnw test                                        # integration suite
RUN_S3_TESTS=true ./mvnw test -Dtest=MediaS3RoundTripTests   # opt-in, writes to a real bucket
```

```bash
cd frontend
npx tsc --noEmit        # types
bun run lint            # eslint
bun run build           # production build
```

There are **no mocks** in the backend suite. The parts most likely to break silently — STOMP fan-out
through the broker, subscription authorisation, Flyway agreeing with the entities — are exactly the
parts a mock would hide, so the suite runs against the real containers. `RealtimeContractTests` drives
actual WebSocket clients; `MediaTests` verifies presigned URLs offline, since signing is local
computation.

---

## Project layout

```
whatsapp-clone/
├── compose.yaml              PostgreSQL, Redis, RabbitMQ
├── README.md                 this file
│
├── backend/
│   ├── readme.md             long-form design notes — read this one
│   ├── API.md                REST + STOMP contract, with rationale
│   ├── SPEC.md               the original build spec
│   ├── CHANGES.md            every contract change and what a client must do
│   └── src/main/
│       ├── java/com/whatsappclone/backend/
│       │   ├── common/       ApiResponse, ErrorCode, encryption, RealtimeBroadcaster
│       │   ├── auth/         JwtService, JWT filter, register + login
│       │   ├── user/         User, contact sync
│       │   ├── chat/         Chat, participants, membership rules
│       │   ├── message/      Message, receipts, keyset history, STOMP controller
│       │   ├── media/        S3 presign / complete / download
│       │   ├── presence/     Redis presence
│       │   ├── security/     @CurrentUser, STOMP auth interceptor
│       │   └── config/       Security, WebSocket, MVC
│       └── resources/db/migration/    Flyway
│
└── frontend/
    ├── app/
    │   ├── (auth)/           login, register
    │   ├── (app)/            chats, contacts, settings
    │   └── api/              auth routes + the /api/b proxy
    ├── components/           chat-view, message-bubble, composer, inbox, nav rail
    └── lib/
        ├── api/              typed client, session, error mapping
        ├── chat/             cache keys, display types, ordering helpers
        └── realtime/         socket lifecycle, provider that patches the cache
```

---

## The realtime design, in one page

**Connect.** The browser opens `ws://localhost:8080/ws` and authenticates with the **httpOnly session
cookie**, not a token in the URL or a header. The JWT never reaches client JavaScript — an XSS bug
cannot exfiltrate it, because there is nothing to exfiltrate.

**Subscribe.** On connect the client subscribes to its own six topics:

```
/topic/user.{id}.messages      new and updated messages
/topic/user.{id}.receipts      delivery and read ticks
/topic/user.{id}.typing        typing indicators (with a TTL, since a client can vanish mid-compose)
/topic/user.{id}.presence      online / last seen
/topic/user.{id}.read-state    read cursors, so other tabs stay in sync
/topic/user.{id}.errors        rejected frames, reported instead of swallowed
```

**Send.** The client publishes to `/app/chat.send`, renders the message immediately with a
`pending-<uuid>` id, and reconciles when the server echoes the real id back. Replaying the same
`clientMessageId` is idempotent, so a reconnect cannot duplicate a message.

**Rejected frames are reported, not swallowed.** An invalid inbound frame used to vanish — the handler
logged and returned, and the client saw nothing. The obvious fix, deleting the
`@MessageExceptionHandler` and letting the transport raise a STOMP `ERROR`, is strictly worse:
Spring's `processHandlerMethodException` swallows unhandled exceptions, and the fallback
`sendErrorMessage` emits its ERROR frame *and closes the socket*. So the handler stays and reports
explicitly on `/topic/user.{id}.errors`, with the same `ErrorCode` vocabulary as REST and the
client's `receipt` header echoed back — keeping the connection open, which is the part that matters
mid-send.

Full reasoning, including the failure modes found by reading `spring-messaging`'s source rather than
its docs, is in [`backend/readme.md`](backend/readme.md#real-time-design).

---

## Security model

- **Stateless JWT**, verified by a dedicated filter. `SecurityContext` is never persisted.
- **BCrypt** password hashing. Phone numbers stored plaintext for display *and* SHA-256 hashed for
  matching.
- **Membership checks on every chat read and write.** Non-members get `403`.
- **STOMP `SUBSCRIBE` authorisation.** A user cannot listen to another user's topic.
- **Media is private.** Objects are reachable only through short-lived signed URLs. Object keys are
  never an authorisation input — download is authorised by `messageId`, and an upload key must belong
  to the sender attaching it, or the whole chat could download it.
- **Content encrypted at rest** with AES-256-GCM. This is server-side encryption and is explicitly
  **not** end-to-end: the application decrypts on read. There is no E2E support.
- **Secrets never enter the browser or the repository.** `.env`, `*.csv`, `*.pem` and `*.key` are
  git-ignored; `.env.example` files are the committed templates. AWS credentials resolve from the SDK
  default chain, never from frontend configuration.

---

## Known limitations

Listed deliberately, so they are not mistaken for oversights:

- **No end-to-end encryption.** Content is encrypted at rest and decrypted server-side on read.
- **No refresh tokens and no logout revocation.** A `logout` clears the cookie; a stolen token stays
  valid until it expires (30 days).
- **No rate limiting** on auth, message send, or upload presigning.
- **Presence can go stale.** `isOnline` reflects live sessions; if the process dies while a user is
  connected the flag stays `true` until that user's next connection corrects it. There is no
  reconciliation job.
- **Chat-topic subscriptions are not membership-checked.** Per-user topics are, which is why
  read-state and error channels live there. Chat-topic payloads carry only a message id and a status,
  but it is still information disclosure.
- **One signed URL per media bubble.** By design — URLs are deliberately not cache-friendly — so a
  thread of images costs a round trip each. A CDN in front of the bucket removes it.
- **`replyToId` is stored and returned but unused by the UI.**

---

## Further reading

| File | What is in it |
| --- | --- |
| [`backend/readme.md`](backend/readme.md) | The long form: data-model decisions, realtime pitfalls, security |
| [`backend/API.md`](backend/API.md) | Every endpoint, frame and error code, with rationale |
| [`backend/CHANGES.md`](backend/CHANGES.md) | Contract changes and what a client must do about them |
| [`backend/SPEC.md`](backend/SPEC.md) | The original build spec |
| [`frontend/PLAN.md`](frontend/PLAN.md) | Locked frontend scope and decisions |
| [`frontend/AGENTS.md`](frontend/AGENTS.md) | Next.js 16 conventions this project follows |

## Contributing

This is a teaching project, so the bar for a change is that it is **understood**. If you are fixing a
bug or adding a feature, please include the reasoning in the pull request — especially the reasoning
for what you replaced. A change that is correct but unexplained is worth less here than one that is
explained.

## License

Released for educational use.