# Places BFF Design

**Date:** 2026-04-24  
**Status:** Approved

## Problem

`NEXT_PUBLIC_GOOGLE_MAPS_KEY` is exposed in the browser bundle. Anyone can scrape it and run up the Google Cloud bill. The 160ms client debounce also fires too aggressively, and session tokens are created but never closed — paying per-request rates instead of the cheaper per-session rate.

## Goal

- Move the Google Maps key server-side
- Rate-limit autocomplete calls via Upstash Redis
- Close Places sessions properly to reduce billing
- Store location as "Place Name, Locality" (e.g. "McDonald's, Greenacre NSW") — not a full street address

## Architecture

Two Next.js Route Handlers under `src/app/api/places/`:

| Endpoint | Purpose | Rate limited |
|---|---|---|
| `GET /api/places/autocomplete?q=...&sessionToken=...` | Proxy to Google Places Autocomplete (New) | Yes |
| `GET /api/places/details?placeId=...&sessionToken=...` | Close billing session (fire-and-forget) | No |

`GOOGLE_MAPS_KEY` (server-only, no `NEXT_PUBLIC_` prefix) and Upstash credentials never reach the browser.

## Route Handlers

### `GET /api/places/autocomplete`

1. Read `SESSION` cookie — return `401` if absent
2. Check Upstash rate limits (keyed by session cookie value):
   - Sliding window: 1 request per 300ms
   - Fixed window: 50 requests per hour
   - Either exceeded → `429` with `Retry-After: <seconds>`
3. If `q` is empty → return `[]`
4. Forward `{ input: q, sessionToken }` to `https://places.googleapis.com/v1/places:autocomplete` with `X-Goog-Api-Key: GOOGLE_MAPS_KEY`
5. Map response to `PlaceSuggestion[]` (`{ id, title, subtitle }`)
6. Return result (no CDN caching — session token is unique per form mount, so identical URL cache hits never occur across users)

### `GET /api/places/details`

1. Forward `placeId` and `sessionToken` to `https://places.googleapis.com/v1/places/${placeId}` with field mask `id` only (Basic SKU, cheapest)
2. Return `204 No Content` — response body is not used
3. Called fire-and-forget from the client; no await needed

## Client Changes

### `src/lib/places.ts`

- `searchPlaces(query, sessionToken, signal)`:
  - Calls `GET /api/places/autocomplete` instead of Google directly
  - On `429`: reads `Retry-After` header, sets module-level `blockedUntil = Date.now() + retryAfter * 1000`, returns `{ rateLimited: true, retryAfter: number }`
  - On subsequent calls while `Date.now() < blockedUntil`: returns `{ rateLimited: true, retryAfter: remainingMs / 1000 }` immediately without fetching
  - On other error or non-OK: returns `[]`
- New `fetchPlaceDetails(placeId, sessionToken)`:
  - Calls `GET /api/places/details` fire-and-forget (no await at call site)
  - Errors are swallowed — failure has no user-visible impact
- `GOOGLE_MAPS_KEY` / `NEXT_PUBLIC_GOOGLE_MAPS_KEY` reference removed
- Mock mode path unchanged

### `src/components/ui/LocationAutocomplete.tsx`

**Debounce:** 160ms → 300ms

**Rate limit handling** (in the debounced `useEffect`):
- If `searchPlaces` returns `{ rateLimited: true, retryAfter }`:
  - Keep current `suggestions` state (list stays visible)
  - **`retryAfter ≤ 10s`** (300ms sliding window case): keep current suggestions, set `loading: true` (spinner), no message. Auto-retry after backoff.
  - **`10s < retryAfter ≤ 60s`**: keep current suggestions, set `loading: true`, show inline message inside dropdown ("⏱ too many searches — try again shortly"). Auto-retry after backoff.
  - **`retryAfter > 60s`** (hourly cap, long lockout): close dropdown, clear the location value (`onChange("")`), disable the input, show inline message below the input ("location unavailable — too many searches. try again later"). Auto-retry after backoff — when it fires, re-enable the input. No location value is passed when the event form is submitted during this state.
  - **All cases**: schedule a single auto-retry: `retryTimeoutRef.current = setTimeout(() => { /* re-run search, re-enable input */ }, retryAfter * 1000)`. If user types before timeout fires, cancel it (debounce takes over). Clear on unmount. At most one auto-retry fires.

**On pick:**
1. Call `onChange(suggestionToLocation(s))` immediately — stored value is `"${title}, ${subtitle}"` (e.g. "McDonald's, Greenacre NSW")
2. Call `fetchPlaceDetails(s.id, sessionToken)` fire-and-forget to close the billing session
3. Close dropdown

## Environment Variables

| Variable | Scope | Purpose |
|---|---|---|
| `GOOGLE_MAPS_KEY` | Server-only | Google Maps Platform key (replaces `NEXT_PUBLIC_GOOGLE_MAPS_KEY`) |
| `UPSTASH_REDIS_REST_URL` | Server-only | Upstash Redis REST endpoint |
| `UPSTASH_REDIS_REST_TOKEN` | Server-only | Upstash Redis auth token |

Remove `NEXT_PUBLIC_GOOGLE_MAPS_KEY` from `.env.local.example` and Vercel project settings.

## Rate Limiting Detail

Two independent Upstash limiters, both keyed by `SESSION` cookie value:

- **300ms sliding window** — prevents burst from rapid keystrokes that slip past client debounce. `Retry-After` will be < 1s; no toast shown.
- **50/hour fixed window** — hard cap per user per hour (~4–6 venue searches). `Retry-After` will be up to 3600s; toast is shown.

Upstash unavailable → fail open (allow the request through, log a warning).

## Error Handling Summary

| Scenario | Server response | Client behaviour |
|---|---|---|
| No SESSION cookie | 401 | Middleware redirects to /login before reaching the handler |
| Rate limited (≤10s) | 429 + Retry-After | Keep list, spinner, auto-retry after backoff |
| Rate limited (10–60s) | 429 + Retry-After | Keep list, spinner, inline dropdown warning, auto-retry after backoff |
| Rate limited (>60s) | 429 + Retry-After | Clear + disable input, inline message below input, auto-retry to re-enable; location omitted from form submission |
| Google API error | 200 `[]` | Dropdown stays empty / previous results persist |
| Upstash unavailable | — | Fail open, request passes through |
| `GOOGLE_MAPS_KEY` unset or mock mode | — | Falls back to static mock suggestions |

## Testing

**Route handlers** (unit, mock Upstash + mock fetch):
- Missing `SESSION` cookie → 401
- 300ms rate limit exceeded → 429 with correct `Retry-After`
- 50/hour rate limit exceeded → 429 with correct `Retry-After`
- Upstash throws → request passes through (fail open)
- Google returns non-OK → `[]`
- Happy path → correct `PlaceSuggestion[]` shape

**`places.ts`** (unit, mock fetch):
- 429 response sets `blockedUntil` correctly
- Subsequent call within block window returns `{ rateLimited: true }` without fetching
- Call after block window fetches normally

**`LocationAutocomplete`** — mock mode path unchanged; no new component tests required.

## Files Changed

| File | Change |
|---|---|
| `src/app/api/places/autocomplete/route.ts` | New |
| `src/app/api/places/details/route.ts` | New |
| `src/lib/places.ts` | Update `searchPlaces`, add `fetchPlaceDetails`, remove key reference |
| `src/components/ui/LocationAutocomplete.tsx` | Debounce 300ms, rate-limit handling, fire-and-forget details call |
| `frontend/.env.local.example` | Replace `NEXT_PUBLIC_GOOGLE_MAPS_KEY` with `GOOGLE_MAPS_KEY` + Upstash vars |
| `package.json` | Add `@upstash/ratelimit` + `@upstash/redis` |
