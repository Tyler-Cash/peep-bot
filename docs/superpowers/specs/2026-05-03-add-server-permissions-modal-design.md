# Add-Server Permissions Modal — Design

**Date:** 2026-05-03
**Branch:** feature/multi-guild

## Problem

Today, both "Add a server" CTAs (the `GuildSwitcher` dropdown and the
`EventsFeed` empty state) open the Discord bot install URL directly via a
plain link. Two issues:

1. The user gets no preview of which permissions Peep Bot is asking for or
   why each one is needed before they hit Discord's authorize screen.
2. The link uses a plain new tab/full navigation rather than a popup, which
   is inconsistent with the OAuth login flow added in `181a5a1`.

## Goal

Clicking either "Add a server" CTA opens an in-app modal that:

- Lists every Discord permission the bot will request, with a short
  human-readable reason for each.
- Has a "Continue to Discord" button that opens the install URL in a
  popup window.
- Lets the user dismiss the modal manually after they finish installing
  in the popup. SWR's existing `revalidateOnFocus` refreshes `/guilds`
  when focus returns to the app, so the new guild appears without
  explicit popup-close polling.

## Non-goals

- Auto-closing the modal once a new guild is detected.
- Auto-closing the Discord popup (no `redirect_uri` is configured for
  bot install — Discord shows its own success page).
- Reworking the permissions themselves. The modal is a presentation of
  the existing `BotPermissions.REQUIRED` bitmask.

## Design

### Backend — structured permission metadata

Convert `backend/src/main/java/dev/tylercash/event/discord/BotPermissions.java`
from a class of `static final long` constants into an enum
`BotPermission`. Each enum entry carries:

- `long bit` — the Discord permission flag (unchanged values).
- `String displayName` — human-readable label, e.g. "Manage roles".
- `String reason` — derived from the existing inline comments, e.g.
  "Per-event Accepted / Declined / Maybe roles".

The existing `REQUIRED` constant becomes a derived value:

```java
public static final long REQUIRED =
    Arrays.stream(BotPermission.values())
          .mapToLong(p -> p.bit)
          .reduce(0L, (a, b) -> a | b);
```

`InstallUrlController` returns a richer DTO:

```json
{
  "url": "https://discord.com/api/oauth2/authorize?client_id=…&permissions=…&scope=bot+applications.commands",
  "permissions": [
    { "name": "View channels",     "reason": "Look up channels and categories" },
    { "name": "Manage channels",   "reason": "Create, sort, and archive event channels" },
    { "name": "Manage roles",      "reason": "Per-event Accepted / Declined / Maybe roles" },
    { "name": "Send messages",     "reason": "Event message and RSVP / cancel notifications" },
    { "name": "Embed links",       "reason": "Event embed body" },
    { "name": "Attach files",      "reason": "Contract chart PNGs" },
    { "name": "Pin messages",      "reason": "Pin event messages in their channel" },
    { "name": "Read message history", "reason": "Scanning existing channels for sort" },
    { "name": "Use external emojis", "reason": "Custom guild emoji in buttons" },
    { "name": "Mention everyone",  "reason": "Ping the @events role" }
  ]
}
```

The OpenAPI generator picks up the new response shape; the frontend
client types in `frontend/src/lib/api/generated/` regenerate.

### Frontend — `AddServerModal`

New component: `frontend/src/components/nav/AddServerModal.tsx`.

- Props: `{ open: boolean; onClose: () => void }`.
- Internally calls `useInstallUrl()` to fetch `{ url, permissions }`.
- Layout: backdrop (click to dismiss) + centered card using project
  tokens (`rounded-card border-[1.5px] border-ink bg-paper shadow-rest`,
  max-width ~520px).
- Sections:
  - Header: "Add Peep Bot to a server" + close `×`.
  - Intro: "We'll open Discord in a popup. Here's what Peep Bot will be
    allowed to do, and why."
  - Table: two columns — *Permission* / *Why we need it* — rendered
    from `installUrl.permissions`. Loading skeleton while `installUrl`
    is undefined.
  - Footer: primary `Chunky` "Continue to Discord" + secondary "Cancel".
- Continue handler: `window.open(installUrl.url, 'peep-install',
  'popup,width=520,height=720')`. After opening, the primary button
  swaps to "Waiting for install…" with a hint line: "Close the Discord
  popup once you've added the bot."
- Accessibility: `role="dialog"`, `aria-modal="true"`,
  `aria-labelledby` on the heading, focus trap (move focus to close
  button on open, restore previous focus on close), `Escape` dismisses.

### Integration

Both call sites switch from a link to a button that toggles a local
`addOpen` state and renders `<AddServerModal />`.

- `frontend/src/components/nav/GuildSwitcher.tsx:134-147` — replace the
  `<a href={installUrl?.url} target="_blank">` "Add a server" entry
  with a `<button>` that sets `addOpen = true`. State lives in the
  outer `GuildSwitcher` so the modal isn't unmounted when the dropdown
  closes. Pass `setAddOpen` to `GuildDropdown`.
- `frontend/src/components/feed/EventsFeed.tsx:19-35` — replace the
  empty-state `<Link href={installUrl}>` with a `<button>` that opens
  the modal. Drop the now-unused `useInstallUrl` import here; the
  modal owns that fetch.

### Revalidation

No popup-close polling. After the user closes Discord's popup and
focuses the app tab, SWR's existing `revalidateOnFocus` refetches
`/guilds`, the new guild appears, and the user dismisses the modal.

## Tests

- **Backend**
  - `BotPermissionsTest` — assert `REQUIRED` bitmask matches the
    pre-refactor value (regression guard so existing installs are
    unaffected).
  - `InstallUrlControllerTest` — assert the response contains `url` and
    a `permissions` array; each `BotPermission` enum entry appears
    with its `name` and `reason`.
- **Frontend unit (Vitest)**
  - `AddServerModal.test.tsx` — renders the table from a mocked
    `useInstallUrl`; "Continue to Discord" calls `window.open` with
    the expected URL and `popup` window features; `Escape` and
    backdrop click dismiss; focus moves into the dialog on open.
  - Update existing `GuildSwitcher` and `EventsFeed` tests to assert
    clicking the CTA opens the modal rather than navigating.
- **Frontend e2e (Playwright)** — extend the empty-state smoke test
  to click "Add to Discord", expect the dialog to render, and assert
  a known permission row (e.g. "Manage roles") is visible. Don't
  assert on `window.open` / popups; MSW can't intercept the Discord
  domain.
- **MSW handler** — update `frontend/src/mocks/handlers.ts:88` to
  return the new `{ url, permissions: […] }` shape.

## Risks

- **OpenAPI regen drift** — the generated client must be regenerated
  before the frontend will type-check. Run the existing codegen task
  as part of the implementation.
- **Modal accessibility** — focus-trap implementations are easy to get
  wrong. Keep the implementation small (focus close button on open;
  restore previously-focused element on close) and rely on the
  Vitest test for coverage rather than reaching for a library.
