# Frontend error surfacing — design

**Date:** 2026-05-24
**Branch:** `worktree-frontend-error-surfacing`

## Problem

The frontend handles errors inconsistently. Some mutations fail silently and leave
optimistic UI updates stuck in a wrong state; the inline errors that do exist show only
a friendly message with no way for a user to tell a dev *which* request failed. There is
no toast mechanism, and the only popup feedback (`RateLimitModal`) is a modal — which we
want to move away from.

## Goals

1. Every error that a user can cause or observe is surfaced to them.
2. Errors tied to a specific request show **inline** (next to the form/control).
3. Errors with no natural inline home show as a **toast**.
4. No new error **modals**; the existing `RateLimitModal` becomes a toast.
5. Every surfaced error carries enough **trace info** for a dev to pinpoint the exact
   failed request: `traceId`, HTTP `status`, `method`, `path`, and a `timestamp`.

## Non-goals

- Changing backend error responses (they already return `{ error, traceId, timestamp }`,
  plus `fieldErrors` on validation failures).
- Reworking the `BackendStatusBanner` (the "homelab is napping" banner stays as-is).
- Changing auth-redirect behavior on 401/403 (`UnauthorizedError` still redirects to
  `/login`; it is not toasted or shown inline).

## Background: what the backend already returns

`backend/.../global/ErrorHandler.java` returns, on every failure:

- Generic / 404 / `ResponseStatusException`: `{ error, traceId, timestamp }`
- Validation (400): `{ status, message, traceId, fieldErrors: [{ field, defaultMessage }] }`

`traceId` is the OpenTelemetry trace id pulled from MDC (see `MdcRequestFilter`,
`logback-spring.xml`) — it correlates directly to logs and traces in Grafana. Today
`lib/api.ts` parses out only the human message and discards `traceId`.

## Architecture

### 1. Error model (`lib/api.ts`)

Capture trace context on the thrown errors instead of discarding it.

- Add a shared type:
  ```ts
  export type ErrorRef = {
    traceId?: string;   // from response body; absent for BackendUnreachable
    status?: number;    // HTTP status; absent for BackendUnreachable
    method: string;     // e.g. "POST"
    path: string;       // e.g. "/event"
    timestamp: string;  // ISO; from body if present, else client clock
  };
  ```
- Extend `ApiError` with `traceId`, `method`, `path`, `timestamp`. Parse `traceId` and
  `timestamp` from the body when constructing it in `apiFetchInner`.
- Extend `BackendUnreachable` with `method`, `path`, and a client-side `timestamp` (it
  never reached the server, so there is no `traceId`/`status`).
- `ResponseShapeError` already carries `path`; expose a client `timestamp` for it too.
- Add helpers:
  - `errorRef(e: unknown): ErrorRef | null` — pulls an `ErrorRef` off any known error type.
  - `describeError(e: unknown): { message: string; ref: ErrorRef | null }` — the **single**
    place mapping each error type to a user-facing message:
    - `BackendUnreachable` → "can't reach the server — check your connection and try again"
    - `ApiError` 429 → rate-limit message (mostly pre-empted by the rate-limit toast)
    - `ApiError` 4xx with a body message → that message
    - `ApiError` 5xx → "something went wrong"
    - `ResponseShapeError` → "got an unexpected response from the server"
  - `UnauthorizedError` is intentionally **not** mapped here — callers `return` early on it
    (the api layer already redirects).

### 2. `<ErrorRef>` component (`components/ui/ErrorRef.tsx`)

The shared trace block, used by **both** inline errors and toasts. Given an `ErrorRef`,
renders compactly in monospace:

```
POST /event · 500
ref a1b2c3d4 · 14:23:01            [copy]
```

- `traceId` is shown truncated for display but copied in full.
- The copy button copies a single line a dev can paste straight into Grafana/log search:
  `traceId=<full> status=500 POST /api/event @ 2026-05-24T14:23:01Z`
- When `traceId`/`status` are absent (BackendUnreachable), it shows what it has
  (`POST /event · unreachable` + time).

### 3. Toasts — Sonner, themed (`components/ui/Toaster.tsx`, `lib/toast.ts`)

- Add the `sonner` dependency.
- `Toaster.tsx` wraps Sonner's `<Toaster>` and themes it to the app's neo-brutalist look:
  `border-[1.5px] border-ink`, `rounded-card`, `shadow-hero`, paper background, Space
  Grotesk type — so it does not read as default Sonner. Mounted in `components/Providers.tsx`.
- `lib/toast.ts`:
  - `toastError(e: unknown)` — runs `describeError`, renders a custom error toast containing
    the message + an `<ErrorRef>` (skips entirely for `UnauthorizedError`).
  - `toastRateLimit(retryAfterSeconds)` — persistent toast with a live countdown (replaces
    `RateLimitModal`), driven by the existing `rateLimitNotifier`.

### 4. Inline error component (`components/ui/InlineError.tsx`)

Extract the duplicated rose-50 alert box (currently copy-pasted in `CreateEventForm` and
`EditEventForm`) into one component taking `{ message, errorRef }`, rendering the message
plus `<ErrorRef>`. This is the "it's a request, put it inline" path.

### 5. Call-site wiring

| Site | Today | Change |
|------|-------|--------|
| `CreateEventForm` | inline field + global, no trace | use `InlineError`; thread `errorRef` |
| `EditEventForm` | single inline string, no trace | use `InlineError`; thread `errorRef` |
| `EventDetail` RSVP (`setStatus`) | silent; optimistic update sticks on failure | `try/catch` → revert via `mutate(undefined,{revalidate:true})` + `toastError` |
| `EventDetail` `confirmRemove` | silent; optimistic update sticks | same revert + `toastError` |
| `EventDetail` `handleCancel` | silent | `toastError` on failure (no optimistic state) |
| `EventDetail` `handleCreatePrivateChannel` | silent | `toastError` |
| `EventDetail` `handleRecategorize` | silent | `toastError` |
| `GuildSettingsForm` `onSave` | silent (`finally` only) | `toastError` on failure |
| `GuildSettingsForm` `onKick` | silent | `toastError` on failure |
| `ReplayModal` | inline `e.message`, no trace | add `<ErrorRef>` to its inline error |
| Load errors: `EventsFeed`, `EventDetail` (404/error), `GuildSettingsForm`, `GalleryFeed` | friendly page state, no trace | add `<ErrorRef>` (from the SWR `error`) so a failed GET is traceable too |
| `Rewind` | **no error state** — shows "loading…" forever on failure | destructure `error` from `useRewind`; add an error branch with message + `<ErrorRef>` |

Note: `EventDetail`'s `setStatus`/`confirmRemove` use optimistic `mutate(..., { revalidate:false })`.
On failure they must trigger a revalidate so the UI snaps back to server truth, then toast.

### 6. RateLimitModal → toast

- Delete the modal/backdrop UI. Add a small subscriber (in `Toaster.tsx` or a sibling hook)
  that listens to `rateLimitNotifier` and calls `toastRateLimit`, keeping the live countdown.
- Remove `<RateLimitModal />` from `Providers.tsx`.

## Error message mapping (summary)

| Error | Inline/Toast message | Ref shown? |
|-------|----------------------|------------|
| `BackendUnreachable` | can't reach the server… | yes (no traceId/status) |
| `ApiError` 4xx (+body msg) | body message | yes |
| `ApiError` 5xx | something went wrong | yes |
| `ApiError` 429 | rate-limit message | via rate-limit toast |
| `ResponseShapeError` | unexpected response | yes (path) |
| `UnauthorizedError` | — (redirects) | n/a |

## Testing

Extend the existing `src/__tests__/render/errors/` suite (Vitest + Testing Library + MSW):

- A failing mutation (e.g. RSVP, save) renders a toast containing the `traceId`.
- Optimistic RSVP reverts to server state when the request fails.
- `ErrorRef` renders the expected display text and copies the expected one-line string.
- An inline form error (Create/Edit) shows message + trace ref.
- A page-level load error shows the trace ref.

MSW handlers return the real backend error shape (`{ error, traceId, timestamp }`) so the
parsing path is exercised.

## Files

**New:** `components/ui/ErrorRef.tsx`, `components/ui/InlineError.tsx`,
`components/ui/Toaster.tsx`, `lib/toast.ts`.

**Modified:** `lib/api.ts`, `components/Providers.tsx`, `components/event/CreateEventForm.tsx`,
`components/event/EditEventForm.tsx`, `components/event/EventDetail.tsx`,
`components/guild/GuildSettingsForm.tsx`, `components/admin/ReplayModal.tsx`,
`components/feed/EventsFeed.tsx`, `components/gallery/GalleryFeed.tsx`,
`components/rewind/Rewind.tsx`, `package.json`.

**Deleted:** `components/RateLimitModal.tsx` (logic folded into the toast layer).
