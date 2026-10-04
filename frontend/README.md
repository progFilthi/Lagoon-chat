# WhatsApp Clone — Frontend

Next.js 16 App Router frontend for the WhatsApp clone. The backend it codes against, including the
full REST and STOMP contract, lives in [`../backend/API.md`](../backend/API.md).

> Looking for the overall picture? Start at the [root README](../README.md).

## Running it

The backend has to be up first — see the [root quick start](../README.md#quick-start).

```bash
bun install
bun run dev        # http://localhost:3000
```

```bash
bun run lint       # eslint
bun run build      # production build
npx tsc --noEmit   # type check
```

## Configuration

Everything is optional. With no `.env.local`, the frontend talks to `http://localhost:8080` and
derives the WebSocket URL from it — correct for local development. Copy `.env.example` to
`.env.local` only if you need to point at a different backend.

| Variable | Scope | Default |
| --- | --- | --- |
| `BACKEND_URL` | server only | `http://localhost:8080` |
| `NEXT_PUBLIC_BACKEND_URL` | browser | derived from the page host |
| `NEXT_PUBLIC_BACKEND_WS_URL` | browser | derived from the page host |

## How it is put together

```
app/
├── (auth)/          login and register — server components
├── (app)/           chats, contacts, settings — session-guarded shell
│   └── chats/[chatId]/
├── api/auth/        login, register, logout route handlers
├── api/b/[...path]  authenticated proxy to the Spring backend
└── layout.tsx       realtime + query providers

components/          chat-view, message-bubble, message-composer,
                     conversation-list, nav-rail, contacts, settings

lib/
├── api/             typed client, session helpers, error mapping
├── chat/            cache keys, display types, ordering + reconciliation
└── realtime/        socket lifecycle and the provider that patches the cache
```

### Four things worth knowing before you change anything

**The JWT never reaches client JavaScript.** Login sets an httpOnly cookie; the WebSocket
authenticates with that cookie during the handshake. There is no token in `localStorage`, so an XSS
bug has nothing to steal.

**The TanStack Query cache is the single source of truth.** The realtime provider patches it in
place — inserting a received message, advancing receipt ticks, marking a chat read — instead of
maintaining a parallel store. Two sources of truth would drift.

**Optimistic messages carry a `pending-<uuid>` id.** Anything that needs a server id must wait for the
echo rather than calling the API with the placeholder; `useAttachmentUrl` is the worked example.

**Design values come from tokens.** `app/globals.css` declares the palette and type scale in
Tailwind v4's `@theme`, which generates the utilities, so a token and its consumers cannot drift
apart. Do not hardcode a hex value in a component.