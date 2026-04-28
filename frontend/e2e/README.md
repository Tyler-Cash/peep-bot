# Playwright smoke tests

Golden-path browser tests for the Peep Bot frontend. They run entirely against the
Next.js dev server in **mock mode** — MSW intercepts every `/api/*` call and serves
a stateful in-memory store. No Spring Boot backend, Postgres, or Discord token is
required.

## Run locally

From `frontend/`:

```bash
npm run test:e2e          # chromium only (fast)
npm run test:e2e:all      # chromium + firefox + webkit
npm run test:e2e:ui       # interactive mode
```

The `webServer` block in `playwright.config.ts` boots `npm run dev` on port 3100
automatically. Set `PLAYWRIGHT_SKIP_WEBSERVER=1` and `PLAYWRIGHT_BASE_URL=...` to
point at a server you've already started.

## How auth works in mock mode

`src/lib/api.ts` defaults to `NEXT_PUBLIC_API_MODE=mock`. Each test starts on
`/login`, clicks "continue with Discord" (which in mock mode just clears a
localStorage flag and routes to `/`), then exercises the rest of the flow. The
mock store is reset by reloading the worker between specs (each spec runs in its
own browser context with a fresh storage state).

## Why not hit the real backend?

The backend hard-requires a Discord bot token to start its JDA listener. Standing
up a real-backend smoke job would either need (a) a no-Discord profile we don't
have, or (b) the same secrets the existing `backend-e2etest` job uses — at which
point we'd be running an integration test, not a UI smoke. Mock mode gives us
fast, deterministic, secret-free coverage of the React/Next.js layer, which is
where almost all of these flows would actually break.
