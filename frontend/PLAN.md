# Frontend plan — locked decisions

Everything decided for the frontend build, and the context needed to pick it up cold. The backend is
merged and documented; this covers what comes next.

**Status:** backend complete and merged to `main` (`4422d46`). Frontend is still the untouched
`create-next-app` scaffold — nothing has been written there yet.

---

## Locked decisions

These were chosen deliberately. Don't relitigate them without a reason.

| Area | Decision | Why |
| --- | --- | --- |
| Session storage | **Route Handler proxy + httpOnly cookie** | The browser never touches the 30-day JWT. Token lives in a cookie the JS can't read, injected server-side per request. The backend has no refresh token and no logout, so this is the entire session story. |
| Data layer | **TanStack Query** | `useInfiniteQuery` maps directly onto the backend's keyset cursor for scrollback, and cache invalidation on socket events is first-class. The Next 16 docs recommend a library over raw `fetch`+`useEffect`. |
| First slice | **Full vertical: auth → chat list → 1:1 chat with live messaging** | Proves the whole contract end to end — unread badges, live delivery, ticks, typing, presence — before groups or media complicate it. |
| Media | **Presigned PUT + presigned GET, private S3 bucket** | No AWS credential reaches a browser; the bytes never touch the backend. Bucket is `whatsapp-clone-filthi`. |
| Verification | **Real integration tests, no mocks** | The parts that break silently are broker fan-out, subscription authorisation, and Flyway/entity agreement — exactly what mocks hide. |

### Auth shape implied by the proxy decision

- `POST /api/auth/login` and `/api/auth/register` as Route Handlers that call the backend, set the
  httpOnly cookie, and redirect.
- A logout Route Handler that clears the cookie. The backend has no logout — discarding the token
  client-side *is* the logout, so this is purely a cookie-clearing route.
- The 30-day JWT has no refresh. Expired means redirect to login and re-authenticate. Handle 401
  globally in the query layer rather than per-call.
- Keep providers as deep as possible — a `(app)` route group, not the root layout, so `/login` does
  not re-render behind an auth check.

---

## Build order

1. **Tokens.** Translate the Paper design system into Tailwind 4 `@theme` in `globals.css`.
2. **API client.** Typed, envelope-aware (`ApiResponse<T>`), unwraps `data`, throws on
   `error.code` — never on `error.message`.
3. **Socket layer.** STOMP over the raw `ws://` endpoint, no SockJS. Subscribe to all per-user
   channels on connect. Must set a `receipt` header on every `SEND`.
4. **Auth screens.** Login and register, E.164 validation matching the backend's
   `^\+[1-9]\d{7,14}$`.
5. **Chat list.** Unread badges, last message preview, presence dots, ordered by `lastMessageAt`.
6. **Chat view.** Keyset scrollback via `useInfiniteQuery`, optimistic send reconciled on
   `clientMessageId`, tick marks from `receipts`, typing indicator.
7. **Wiring.** Presence, unread sync via `read-state`, the `.errors` channel marking failed sends.

---

## Backend contract — the three breaking changes

Full detail in [`../backend/CHANGES.md`](../backend/CHANGES.md). The parts that will break a client
written against the old contract:

**One message shape.** The `chat.send` broadcast is now the same `MessageResponse` history returns.
`SendMessageResponse` is gone. The broadcast carries `content` and `senderId`, so a recipient can
render straight from it and an optimistic bubble reconciles on `clientMessageId` with no refetch.
`senderRole` was dropped — derive it from `senderId` against
`ChatSummaryResponse.participants[].role`.

**Two new per-user channels.** Both require your own user id:

| Destination | Payload | Action |
| --- | --- | --- |
| `/topic/user.{id}.errors` | `{code, message, destination, receipt}` | Subscribe. Mark the pending send failed by `receipt`. Connection stays open. |
| `/topic/user.{id}.read-state` | `{chatId, userId, lastReadAt}` | Subscribe. Zero that chat's badge. |

**One user shape.** `SyncedContact` deleted; `AuthResponse.user` gained `online` and `lastSeen`.
Every user-shaped response is the same record now.

Also: `SendMessagePayload` gained `mediaKey`, and `content` is no longer `@NotBlank` — it is the
caption and may be empty on an attachment.

### Two things that are *not* bugs

Both were investigated and are working as designed — do not "fix" them:

- `limit` on message history **clamps** (`Math.clamp`), so `limit=1000` silently becomes `100`.
- `@Valid @Payload` on `chat.send` **is** enforced, by `PayloadMethodArgumentResolver`,
  independently of `@Validated` on the controller.

---

## Next.js 16.3.8 — verified against the bundled docs

This version breaks things that look familiar. Read
`frontend/node_modules/next/dist/docs/` before writing code; `frontend/AGENTS.md` says the same.

- **`params` / `searchParams` are Promises.** Sync access is fully removed. Use the generated globals
  `PageProps<'/chat/[chatId]'>` and `LayoutProps<'/'>` — no import needed. A client page unwraps with
  `use(props.params)`. A literal route path won't typecheck until the route exists and typegen runs.
- **`useSearchParams()` requires a `<Suspense>` boundary.** `next dev` won't warn you;
  `next build` fails with *Missing Suspense boundary*. Use the page's `searchParams` prop instead
  where possible, and wrap anything else.
- **`error.tsx` takes `retry`, not `reset`.** `retry` is the stable prop as of 16.3.
- **`middleware.ts` is now `proxy.ts`**, and only one per project.
- **`next lint` is gone.** The scaffold already runs `eslint` directly; `next build` no longer lints.
- **Route Handlers cannot host WebSockets.** Connect to the backend's `/ws` from the client directly.
  Route Handlers are the REST layer only.
- **`useEffectEvent` is available** (React 19.2). Use it for socket setup so the connection doesn't
  tear down and reconnect on every state change — this is the idiomatic fix for that exact bug.
- **Server Components must not fetch from Route Handlers** — prerender fails. Fetch from the backend
  directly, or keep the data client-side.
- **`NEXT_PUBLIC_*` is frozen at build time** and dynamic lookups (`process.env[name]`) are *not*
  inlined. Don't put anything you need to change at deploy time behind it.
- Turbopack is the default for both `dev` and `build`. No `--turbopack` flag needed.
- Optional: `typedRoutes: true` validates every `href`. Worth it once routes exist.

---

## Environment notes

**`backend/.env` still holds the pre-rotation AWS keys.** The keys were rotated in AWS, so the values
in that file are now stale and media calls will fail with a credentials error. Update them before any
media work. Media stays disabled while `MEDIA_BUCKET` is empty, so text messaging is unaffected.

**Docker was shut down.** Bring it back before running anything:

```bash
docker compose up -d          # postgres, redis, rabbitmq
cd backend && ./mvnw test     # 13 tests, 1 skipped by default
```

If Postgres refuses to start, a hard shutdown leaves a stale `postmaster.pid` in the
`postgres_data` volume — remove it and recreate the container. Noted in `backend/readme.md`.

CORS allows `http://localhost:3000`, which matches the dev server.

---

## Design system — Lagoon, light only

**Light mode only.** There is no dark theme in v1 and no plan for one. Do not add a `dark:` variant,
a colour-scheme toggle, or a second token namespace. One ground (`--msg-ground` #FFFFFF), one
conversation surface (`--msg-surface` #F2F6F4), one accent.

Paper holds the system as **`--msg-*` design tokens — 81 of them, already created.** They are no
longer a description in this document; they are the source of truth, and build step 1 is a
translation of `get_tokens({format: "tailwind"})` into `globals.css`.

Built so far in Paper: the DS board (palette, type, bubbles, conversation row, controls, presence &
icons) and the desktop Chats screen. Auth screens follow.

### What the token layer corrected

Four colours the DS board's palette section never documented but the components actually use, now
tokens: `--msg-hairline` #D8E2DF, `--msg-accent-tint-border` #B3E3D2, `--msg-surface-muted`
#E4EBE8, `--msg-thread-top` #CBD8D4.

Three accessibility rules the token descriptions now carry, because the drawings broke all three:

- **`--msg-accent` #0BA37F is 3.2:1 on chalk.** Graphics, borders, focus rings and large text only.
  Small text uses `--msg-accent-text` #066B52 (6.5:1). The DS used jade for the `typing…` preview,
  the `online` label and the sender-name prefix; all three now use the readable form.
- **`--msg-live` #FF6B57 is 2.8:1 on chalk.** Dots and pills only. Anything readable uses
  `--msg-live-text` #B24B3D (5.29:1).
- **`--msg-ink-muted` #5E6C69** is 66% sea ink, chosen because it clears AA on both ground *and*
  surface. At 62% it would have failed on `--msg-surface`.

### Scope: no calls

v1 is auth, the conversation list, 1:1 and group chats, image attachments via presigned S3, and
live messaging over the existing WebSocket contract. **Calls are not in v1.** The Paper file still
has a `Lagoon Web — Calls` artboard and call affordances on the Chats screen (thread-header video
and phone buttons, the nav-rail phone icon, "Calls" and "Pinned" filter chips); these are stale and
must not be built. There is no Sky/calls token — `--msg-accent` is the only accent.

`/api/chats` returns no pinned field, so the "Pinned" chip has nothing behind it either.

### Copy must come from the server, not the mock

The Chats artboard's placeholder rows disagree with `ChatService.previewOf`, which generates the
preview string server-side:

| Drawn placeholder | What the API actually returns |
| --- | --- |
| "Sent a photo" | `Photo` |
| "Voice message · 0:24" | `Audio` |
| "Missed outgoing call" | *(no calls in v1)* |
| raw phone number as a preview | the decrypted content, first line |

An attachment with a caption renders as `Photo: <caption>`. The sender prefix in a group preview
("Kofi: …") is **not** in `lastMessagePreview` — build it client-side from `lastMessageSenderId`
against `participants[]`. Do not hardcode the drawn strings into components.