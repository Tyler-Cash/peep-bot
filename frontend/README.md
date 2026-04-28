# peepbot — frontend (Next.js, Vercel-hosted)

The redesigned web app. Replaces the legacy Vite SPA (preserved at `../frontend-legacy/` for reference until we delete it).

## Setup

```bash
npm install
npx msw init public/            # generates public/mockServiceWorker.js
cp .env.local.example .env.local
npm run dev
```

Open http://localhost:3000 — the app runs against the **in-browser MSW mock** by default, so no backend is required.

## Modes

`NEXT_PUBLIC_API_MODE` in `.env.local`:

- `mock` (default) — MSW intercepts all `/api/*` calls. Seeded fixtures live in `src/mocks/fixtures.ts`; RSVP / create-event mutate that in-memory store for the lifetime of the tab.
- `live` — `fetch` hits `NEXT_PUBLIC_API_BASE` (e.g. `https://api.tylercash.dev` or `http://localhost:8080/api` for local backend). Spring session cookie + Discord OAuth required. Middleware redirects to `/login` when the `SESSION` cookie is missing.

## Structure

```
src/
├── app/                         # App Router
│   ├── layout.tsx               # Space Grotesk, providers
│   ├── page.tsx                 # /  — events feed
│   ├── login/page.tsx
│   ├── events/new/page.tsx
│   ├── events/[id]/page.tsx
│   ├── rewind/page.tsx
│   └── globals.css
├── components/                  # Reusable UI
│   ├── ui/                      # Chunky, Slab, Avatar, Avas, CatTag, ReactionRow, CountdownChip, DayMarker
│   ├── nav/                     # Nav + GuildSwitcher
│   ├── feed/                    # EventsFeed + FeedCard
│   ├── event/                   # EventDetail + CreateEventForm + RsvpGroup
│   ├── rewind/
│   ├── login/
│   ├── Peepo.tsx                # mascot
│   └── AppShell.tsx             # shared layout (nav + offline banner)
├── lib/
│   ├── api.ts                   # fetch wrapper w/ CSRF, 401 handling, 429 retry
│   ├── hooks.ts                 # SWR hooks (all cache keys namespaced by guild id)
│   ├── types.ts                 # DTOs — match the backend API shape
│   ├── categories.ts            # 6-category palette + emoji map
│   ├── format.ts                # date, countdown, initials helpers
│   └── swrCache.ts              # localStorage-backed SWR cache (offline-tolerant)
└── mocks/                       # MSW worker, handlers, fixtures
```

## Design tokens

See `tailwind.config.ts` — colors (`paper`, `ink`, `leaf`, `cat-*`), `chunky-*` box shadows, `border-[1.5px]` width, Space Grotesk font family. Source: `design_handoff_peepbot/README.md`.

## Deployment

- Vercel project roots at `frontend/`.
- Env: `NEXT_PUBLIC_API_MODE=mock` for initial previews (works with no homelab); flip to `live` + set `NEXT_PUBLIC_API_BASE=https://api.tylercash.dev` for the production deploy.
- DNS: `app.tylercash.dev` → Vercel. Because frontend and backend share the `tylercash.dev` parent domain, Spring session cookies (`SameSite=Lax`) work cross-subdomain without any backend changes.
- Backend config: add `https://app.tylercash.dev` to `dev.tylercash.cors.allowed-origins`, set `dev.tylercash.frontend.url=https://app.tylercash.dev`.

## Offline behaviour

When the homelab is unreachable (live mode):

- SWR serves the last successful response from `localStorage` (via `swrCache.ts`).
- `<BackendStatusBanner>` polls `/api/auth/is-logged-in` every 30s and shows a banner when it fails.
- Mutations that fail with `BackendUnreachable` fall through — today they surface an error; follow-up work: persistent retry queue.
