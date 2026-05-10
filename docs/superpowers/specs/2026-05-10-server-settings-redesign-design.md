# Server Settings Page Redesign

## Goal

Replace the tabbed `GuildSettingsForm` with a single scrolling, two-column layout
matching the high-fidelity prototype in `design_handoff_server_settings/` (zip
shared 2026-05-10). The redesign also introduces four new persisted fields
(planned events category, archived events category, archive retention,
"anyone can create" toggle) and a "kick peepbot" danger action.

## Scope

In:

- New backend schema fields and validation.
- New backend endpoints for live Discord roles + categories and a kick-bot
  action.
- Per-guild plumbing of the planned + archived category and `archive_days`
  through the existing event lifecycle (scheduler + archive listener +
  `DiscordService`).
- Full rewrite of `frontend/src/components/guild/GuildSettingsForm.tsx` and
  associated tests.
- New shared UI primitives (`ChipPicker`, `SegmentedSelector`, `CFSliderLight`,
  `MapPreview`).
- MSW mock handlers for the new endpoints.

Out (explicit follow-ups, not this spec):

- Custom guild emoji in the RSVP slot catalog.
- Real map tiles (Mapbox / Google Static) for the location card.
- Migrating `events_role` / `organiser_role` from names to snowflakes (separate
  migration, larger blast radius).

## Reasonable defaults / decisions

1. **Categories persist as Discord snowflake strings**, not names.
   Names are not unique within a guild and rename without notification.
   Two new nullable columns: `planned_category_id`, `archived_category_id`.
2. **Roles stay on names** for this spec (`events_role`, `organiser_role`
   already store names; the bot reads them by name in
   `DiscordRoleService#getRolesByName`). ChipPickers for roles bind to names.
3. **`anyone_can_create` defaults to `true`** to preserve existing behaviour —
   any member can create an event today; the rate limit gate already applies to
   everyone.
4. **`archive_days` is constrained to `{7, 14, 30, 90}`** with a CHECK and a
   default of 90.
5. **`event_create_rate_limit_per_hour` is forced to NULL when
   `anyone_can_create=false`.** Organisers bypass the limiter today, so the
   value is meaningless in that mode and persisting a stale number is
   confusing.
6. **Auto-archive job is in scope.** The existing `EventTickScheduler` +
   `EventArchiveListener` are updated to honour the configured per-guild
   `archive_days` and `archived_category_id`. See "Auto-archive job" below.
7. **Kick bot endpoint requires guild-name confirmation** in the request
   body. Owner-only. The existing
   `GuildLifecycleListener#onGuildLeave(GuildLeaveEvent)` already handles
   cleanup (events role / `#outings` removal) — the new endpoint is just a
   trigger for `JDA#leave()`.
8. **Live data endpoints return `[{id,name}]`** sorted alphabetically by
   `name`. UI displays `name`, persists either `id` (categories) or `name`
   (roles).

## Backend changes

### Liquibase migration

Add to `db/changelog/db.changelog-master.yaml` (or current entry-point
include) a new changeset adding four columns to `guild`:

```sql
ALTER TABLE guild
  ADD COLUMN planned_category_id   TEXT,
  ADD COLUMN archived_category_id  TEXT,
  ADD COLUMN archive_days          INTEGER NOT NULL DEFAULT 90
                                   CHECK (archive_days IN (7, 14, 30, 90)),
  ADD COLUMN anyone_can_create     BOOLEAN NOT NULL DEFAULT TRUE;
```

### Entity / DTOs

- `Guild.java`: add four fields with getters/setters; `Guild.withDefaults`
  initialises `archive_days = 90`, `anyone_can_create = true`, both category
  ids null.
- `GuildSettingsDto`: add `plannedCategoryId`, `archivedCategoryId`,
  `archiveDays`, `anyoneCanCreate`.
- `GuildSettingsRequest`: same four fields, all optional (`null` = no change
  for primitives that already default; explicit `null` for category ids
  clears them).

### Controller validation

In `GuildSettingsController#updateSettings`:

- Validate `archiveDays ∈ {7, 14, 30, 90}` when present → 400.
- Validate `plannedCategoryId != archivedCategoryId` (when both non-null) →
  400 with message `"planned and archived categories must differ"`.
- When `anyoneCanCreate=false`, force-clear `event_create_rate_limit_per_hour`
  to `null` regardless of request value.
- Persist new fields (treat `null` for categories as a clear; primitives keep
  their column defaults if absent).

Existing tests for the controller extend with new field coverage and the
cross-validation case.

### New `GuildDirectoryController`

New file `backend/src/main/java/dev/tylercash/event/discord/GuildDirectoryController.java`:

```java
@RestController
@RequestMapping("/guild/{guildId}")
class GuildDirectoryController {
  // GET /roles      → List<DirectoryEntry> sorted by name
  // GET /categories → List<DirectoryEntry> sorted by name
}
record DirectoryEntry(String id, String name) {}
```

Both endpoints:

- Resolve `guildIdLong`.
- Call `guildMembershipService.assertMember(snowflake, guildIdLong)`.
- Read from `JDA#getGuildById(...)` (skip `@everyone` for roles; skip
  managed/integration roles).
- Return `200 [{id,name}, ...]`. If `jda.getGuildById` is null (cache miss /
  bot not in guild), return **`200 []`** so the frontend renders an empty
  picker rather than a hard error. Add a debug log.

New `GuildDirectoryControllerTest` covers: member auth, sorting, empty
response when JDA cache is cold, role exclusions.

### Kick bot endpoint

New `DELETE /guild/{guildId}` on `GuildController` (existing class):

```java
@DeleteMapping("/{guildId}")
ResponseEntity<Void> kick(
    @PathVariable String guildId,
    @RequestBody KickGuildRequest body,
    @AuthenticationPrincipal OAuth2User principal);
record KickGuildRequest(String confirmGuildName) {}
```

- Owner-only via `discordAuthService.isGuildOwner(guildIdLong, userId)`. 403
  otherwise.
- `assertMember` first.
- Resolve the live JDA guild; reject with 404 when not found.
- Compare `body.confirmGuildName().trim()` (case-insensitive) against
  `jdaGuild.getName().trim()`. 400 on mismatch with message
  `"guild name confirmation does not match"`.
- Call `jdaGuild.leave().queue()`. Return 204.

Existing `GuildLeaveEvent` listener handles the events-role and channel
cleanup — we do not duplicate that logic here. The plan must verify the
listener exists; if it does not, surface a follow-up rather than expand scope.

New test class for the endpoint: owner gate, name-match gate, success path
issues `leave()`, persisted guild row eventually removed by listener (covered
by mocking JDA or asserting the call).

## Frontend changes

### File layout

Replace the body of `frontend/src/components/guild/GuildSettingsForm.tsx`.
New child components live in `frontend/src/components/guild/settings/`:

- `RsvpEmojiCard.tsx`
- `CategoriesArchiveCard.tsx`
- `PrimaryLocationCard.tsx`
- `RolesPermissionsCard.tsx`
- `CreationThrottleCard.tsx`
- `DangerZoneCard.tsx`
- `KickBotConfirmModal.tsx`
- `StickySaveBar.tsx`

New shared primitives in `frontend/src/components/ui/`:

- `ChipPicker.tsx` — keyboard-navigable combobox-style chip list, supports
  disabled+struck-through options for cross-validation.
- `SegmentedSelector.tsx` — used both for archive-days (with optional
  `defaultPill` prop on one option) and the 2-up "anyone / organisers" card
  variant.
- `CFSliderLight.tsx` — range slider with custom track/thumb shadow tokens.
- `MapPreview.tsx` — placeholder card with a simple pin glyph
  (📍 inside a paper2 panel) and the configured location name beneath. The
  bespoke Port Phillip Bay SVG is **not** copied across; replacing the
  placeholder with a real map (or the SVG, if we want it back) is a
  follow-up. The card still uses the `1.5px` ink border + `4px 4px 0 ink`
  shadow shell so it lands in the right visual slot.

### State shape

```ts
type GuildSettings = {
  rsvpEmoji: { accept: string; decline: string; maybe: string };
  primaryLocation: string;
  primaryLocationPlaceId: string | null;
  primaryLocationLat: number | null;
  primaryLocationLng: number | null;
  notifRole: string;        // role name
  organiserRole: string;    // role name
  anyoneCanCreate: boolean;
  rateLimit: number | null; // 1–10; null = use default; only meaningful when anyoneCanCreate
  rateLimitDefault: number;
  plannedCategoryId: string | null;
  archivedCategoryId: string | null;
  archiveDays: 7 | 14 | 30 | 90;
};
```

### Hooks

Extend `frontend/src/lib/hooks.ts`:

- `useGuildRoles(guildId)` → `SWR` over `GET /guild/{id}/roles`.
- `useGuildCategories(guildId)` → `SWR` over `GET /guild/{id}/categories`.
- `kickBotFromGuild(guildId, confirmGuildName)` → mutator hitting
  `DELETE /guild/{id}`.

Both `use*` hooks return `{ data, isLoading }`. While `isLoading`, ChipPickers
render the "⟳ syncing with discord…" eyebrow + skeleton chips per the
prototype.

### Layout

```
<HeaderBar/>                                     (existing)
<Hero> guild name + member-count subtitle </Hero>
<TwoColumn>
  Left  (flex 1.2):  RsvpEmojiCard
                     CategoriesArchiveCard
  Right (flex 1):    PrimaryLocationCard
                     RolesPermissionsCard
                     CreationThrottleCard?  (anyoneCanCreate only)
                     DangerZoneCard
</TwoColumn>
<StickySaveBar>  cancel · dirty-indicator · save changes  </StickySaveBar>
```

Page padding `32px 56px 56px`, column gap `20px`. All cards use existing
`Slab` shell or a new variant if `Slab` does not support the offset-shadow
sizes called for (`4px 4px 0 ink` default, `5px 5px 0 ink` RSVP/hero, danger
border + shadow on the danger card). Verify in plan; extend `Slab` rather
than fork if the variants are close.

### Cross-validation

In `CategoriesArchiveCard`:

- Pass `disabledIds={[archivedCategoryId]}` to the planned ChipPicker.
- Pass `disabledIds={[plannedCategoryId]}` to the archived ChipPicker.
- ChipPicker renders disabled option as struck-through `mute` chip, not
  selectable.

### Conditional throttle card

`CreationThrottleCard` is unmounted when `anyoneCanCreate === false` — no
disabled state, removed from the DOM, matching the prototype.

### Save UX

- Sticky save bar at `bottom: 24px` with the existing `Chunky` leaf button.
- Dirty-state via shallow diff of working state vs. initial snapshot
  (memoised). Discard reverts to the snapshot.
- Submit calls `updateGuildSettings(guildId, …)` once with the full payload.
  The backend already accepts partial updates; sending the full payload keeps
  the client simple.

### Danger zone

`DangerZoneCard` shows a single full-width danger-styled `Chunky`. Click opens
`KickBotConfirmModal` (built on existing `ConfirmModal` if it supports a
typed-confirmation slot — extend it if not). Modal copy:

> Type **{guildName}** to confirm. Peepbot will leave the server, the events
> role will be deleted, and `#outings` will be removed. Event history stays
> in the rewind.

Confirm button is disabled until the typed value matches the guild name
(case-insensitive trim). On confirm: call `kickBotFromGuild`, on success
navigate to `/`.

### Tests

- Update `__tests__/components/GuildSettingsForm.test.tsx` for the new layout
  (remove tab assertions; assert each card's presence and wiring).
- Update `__tests__/render/GuildSettingsForm.test.tsx` and
  `__tests__/render/errors/GuildSettingsForm.unauthorized.test.tsx` for the
  new copy.
- New `__tests__/components/GuildSettingsForm.kickBot.test.tsx` covering the
  confirm-modal happy path and name-mismatch.
- New `__tests__/components/GuildSettingsForm.crossValidation.test.tsx`
  covering the disabled struck-through chip behaviour.
- MSW handlers in `frontend/src/mocks/handlers.ts` (or current location) for
  the new endpoints with sensible fixtures.

### Tokens

Verify the following Tailwind tokens exist in `tailwind.config`. Add any
missing ones:

`leafDk #4E8A2C`, `leafLt #C8E5B0`, `pond #2F4A1E`, `danger #DC2626`, RSVP
slot bgs `#E6F4D8 #FCE5E8 #F5EDD2`. Existing tokens already cover `ink`,
`ink2`, `ink3`, `paper`, `paper2`, `paper3`, `mute`, `line`, `leaf`,
`discord`, `rose`.

## Auto-archive job

Today, `EventArchiveListener` archives an event channel once
`event.dateTime + 1 day @ 22:00` has passed, and `DiscordService` looks up the
target archive category by hard-coded name (`EVENT_ARCHIVE_CATEGORY`).
Similarly, `EVENT_CATEGORY` is the hard-coded planned-events parent. Both must
become per-guild driven by the new columns.

### Scheduler

`EventTickScheduler.emit()` currently emits `EventArchivalDue` for any
`POST_COMPLETED` event whose `dateTime < now - 22h` and re-checks the precise
threshold inside the listener. Replace the hard-coded threshold with the
configured `guild.archive_days` (default 90):

- Widen the SQL window to `dateTime < now - 7 days` (the smallest legal
  `archive_days`). No `POST_COMPLETED` event younger than 7 days can ever be
  archive-due, so this is a safe upper bound.
- For each candidate event, look up the owning `guild.archive_days` and
  publish `EventArchivalDue` only when
  `now > event.dateTime + guild.archive_days`. The existing tick-log keeps
  this idempotent — a row is only written once we publish.
- Borderline events emit on the next minute tick after their threshold
  passes; no listener-level retry loop is required.

Eager DB-level filtering (a JOIN against `guild.archive_days`) is a possible
optimisation but unnecessary at current event volumes. Reading a few stale
rows per minute is fine.

### Listener

`EventArchiveListener#handle`:

- Drop the existing precise re-check (`event.dateTime + 1d @ 22:00`). The
  scheduler now publishes only when actually due, so a guard inside the
  listener turns into a constant-retry hazard for guilds with large
  `archive_days` (the durable retry poller would burn 24 attempts before
  giving up — wrong for a 90-day delay). The scheduler is the single source
  of timing.
- Resolve the target category id from `guild.archived_category_id`. If null,
  fall back to the legacy `EVENT_ARCHIVE_CATEGORY` name lookup so existing
  guilds that haven't visited the new settings page continue to archive into
  the named category.
- Pass the resolved category through to a new
  `DiscordService#archiveEventChannel(Event event, String archiveCategoryId)`
  overload. The single-arg method keeps its signature for callers that
  haven't been updated, internally resolving from the guild row.

### DiscordService

- `getCategoryByGuild(long guildId)` (new helper): returns the configured
  planned-category by id when set, else falls back to the legacy named
  `EVENT_CATEGORY`.
- `archiveEventChannel(Event)`: resolves archived-category by id from the
  guild row first, then by legacy name if null.
- The orphan-channel sweeper (`DiscordService.java:247`) and the re-parent
  flow (`:294`) likewise consult the guild's configured ids before falling
  back to names.

### Tests

- `EventTickSchedulerTest` (existing or new): an event with `archive_days=7`
  emits at the 7-day mark; an event with `archive_days=90` does not emit at
  the 7-day mark and does emit at the 90-day mark; a guild row with default
  90 still works.
- `EventArchiveListenerTest`: when `archived_category_id` is set, the channel
  moves into that category; when null, falls back to the legacy named
  category. No retry/IllegalStateException path is exercised any more.

### Migration safety

Existing guilds will have null `planned_category_id` / `archived_category_id`
after the migration (the columns are nullable). The fall-back to the legacy
named categories preserves current behaviour until the admin opens the new
settings page and picks values. `archive_days` defaults to 90, which is far
longer than today's 1-day-after-22:00 threshold — this is a behaviour change
to flag in the release notes (events stay in the planned category for ~3
months instead of ~1 day). If we want to preserve current behaviour for
existing guilds, set the migration default to a sentinel (e.g. 1) for
existing rows, but **the spec calls for 90** as the user-facing default
established by the redesign.

## Migration / rollout

- Migration is additive (new nullable / default-valued columns). Rollback is
  a column drop. No data backfill required.
- Frontend ships behind no flag — the redesign replaces the old form
  in-place. `#tab=…` deep-link hashes become inert (no redirect needed).

## Risks

- **JDA cache cold start**: directory endpoints return `[]` if cache is not
  ready, which can confuse a first-time admin. Mitigated by SWR retry +
  skeleton state in the UI.
- **Owner-only kick**: current settings PATCH is also owner-only, so no new
  authorisation surface. Confirmation gate prevents accidental clicks.
- **Stale rate-limit value**: when an admin toggles `anyone_can_create=false`
  then back to `true`, the rate limit will read as null until they pick a
  new value. UI displays the server default in that case (existing
  behaviour for `rateLimit === null`).

## Files touched (preview)

- `backend/src/main/resources/db/changelog/changes/2026-05-10-...yaml` (new)
- `backend/src/main/java/dev/tylercash/event/discord/Guild.java`
- `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsDto.java`
- `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRequest.java`
- `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsController.java`
- `backend/src/main/java/dev/tylercash/event/discord/GuildController.java`
- `backend/src/main/java/dev/tylercash/event/discord/GuildDirectoryController.java` (new)
- `backend/src/main/java/dev/tylercash/event/lifecycle/EventTickScheduler.java`
- `backend/src/main/java/dev/tylercash/event/lifecycle/listener/EventArchiveListener.java`
- `backend/src/main/java/dev/tylercash/event/discord/DiscordService.java`
- `backend/src/test/java/.../GuildSettingsControllerTest.java`
- `backend/src/test/java/.../GuildDirectoryControllerTest.java` (new)
- `backend/src/test/java/.../GuildControllerKickTest.java` (new)
- `frontend/src/components/guild/GuildSettingsForm.tsx`
- `frontend/src/components/guild/settings/*.tsx` (new dir, ~8 files)
- `frontend/src/components/ui/{ChipPicker,SegmentedSelector,CFSliderLight,MapPreview}.tsx` (new)
- `frontend/src/lib/hooks.ts`
- `frontend/src/lib/api.ts` (only if new error shape needed)
- `frontend/src/mocks/handlers.ts`
- `frontend/tailwind.config.*` (token additions if missing)
- `frontend/src/__tests__/components/GuildSettingsForm.*.test.tsx`
- `frontend/src/__tests__/render/GuildSettingsForm.test.tsx`
