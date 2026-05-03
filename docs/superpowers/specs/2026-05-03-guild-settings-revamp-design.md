# Guild Settings Revamp + Per-Guild Event-Create Rate Limit

## Goal

Two coupled changes to `frontend/src/components/guild/GuildSettingsForm.tsx` and the backend that powers it:

1. **Reorganise the settings page** into three tabs so it stops feeling like a flat dump of unrelated knobs.
2. **Move the event-create rate limit from a global config property to per-guild storage**, surfaced as a new field in the new tabbed UI. The existing `dev.tylercash.event.create-rate-limit.per-guild-per-hour` property remains as the fallback when a guild has not chosen its own value.

This work does **not** change any other behavior, does **not** introduce per-guild overrides for the Places spend cap, and does **not** add audit logging.

## Why

The settings page already has 9 fields and a tenth (the per-guild rate limit) is being added. The current layout — two `Slab` panels, the second a kitchen sink of roles / channel / emoji — does not scale. At the same time, it is wrong for one server's chattiness to be governed by a single shared global; per-guild storage is what users actually want.

## Backend

### Schema

A new Liquibase changeset on the `guild` table:

```yaml
- changeSet:
    id: add event_create_rate_limit_per_hour to guild
    author: tyler
    changes:
      - addColumn:
          tableName: guild
          columns:
            - column:
                name: event_create_rate_limit_per_hour
                type: int
```

The column is nullable. **Null** is the meaningful sentinel: "this guild defers to the configured default." It is not backfilled to 5 by the migration — the runtime fallback covers that.

### Domain + DTO

- `Guild` entity gains `private Integer eventCreateRateLimitPerHour;` (boxed Integer so null round-trips).
- `GuildSettingsDto` gains the same field, exposed by `GET /guild/{id}/settings` and accepted by `PUT /guild/{id}/settings`.
- Validation: when non-null, the value must be one of `{3, 5, 7, 10}`. Invalid values return 400. Null is always accepted.

### Limiter

`EventCreateRateLimiter.tryAcquire(long guildId)` currently reads its capacity from `EventCreateRateLimitConfiguration`. After this change:

1. Look up the guild via the existing `GuildRepository`.
2. If `guild.eventCreateRateLimitPerHour != null`, use that as the bucket capacity.
3. Otherwise fall back to `EventCreateRateLimitConfiguration.perGuildPerHour`.
4. Cache the resolved capacity inside the existing per-guild Caffeine bucket entry — the bucket capacity is set at construction, so a value change requires bucket invalidation (next).

When the settings update endpoint persists a new value (or clears it), it must call `eventCreateRateLimiter.invalidate(guildId)` so the next call constructs a fresh bucket with the new capacity. Without this, an admin lowering the cap would still see the old higher cap until the Caffeine entry's `expireAfterAccess` (2h) lapses.

`EventCreateRateLimiter` therefore gets one new method:

```java
public void invalidate(long guildId) {
    guildBuckets.invalidate(guildId);
}
```

`GuildSettingsController` (the existing PUT entry point at `dev.tylercash.event.discord.GuildSettingsController`) calls `eventCreateRateLimiter.invalidate(guildId)` after the repository save returns.

### What stays

- `EventCreateRateLimitConfiguration` and the `dev.tylercash.event.create-rate-limit.per-guild-per-hour` property remain. They are now the **default** rather than the only value. The existing `DEV_TYLERCASH_EVENT_CREATE_RATE_LIMIT_PER_GUILD_PER_HOUR` env var in prod continues to work and continues to govern any guild that has not set its own value.
- The auth-fuzz allowlist needs no change (no new endpoints).

## Frontend

### Tabs

`GuildSettingsForm` is restructured into three tabs:

1. **Roles & channels** — `eventsRole`, `organiserRole`, `separatorChannel`.
2. **RSVP emoji** — `emojiAccepted`, `emojiDeclined`, `emojiMaybe`.
3. **Defaults & limits** — `primaryLocation` (with its place_id/lat/lng), `eventCreateRateLimitPerHour`.

The tab strip is a horizontally scrollable row of `Chunky`-style chip buttons matching the existing design system. It is sticky just below the page header. Active tab is reflected in a URL hash (`#tab=roles`, default `roles`) so reload and back-button preserve the active section.

### Single form, single save

All fields remain in one React form state object. There is one `<form onSubmit>`. A single save bar sits at the bottom of the page (`position: sticky; bottom: 0;` so it stays visible while the user scrolls). The save button is the existing `Chunky` leaf variant; it is disabled until the form is dirty (initial values vs current state). Switching tabs does not lose unsaved edits.

The cancel link in the current layout (top-right `← back`) is preserved at the top; the bottom of the form has `cancel` next to the save button as today.

### New rate-limit field UI

Lives in the *Defaults & limits* tab. Component layout:

```
EVENT-CREATE RATE LIMIT
[ ] Use server default (5 / hour)        ← checkbox, checked by default

When unchecked, reveal:
  ( 3 )  ( 5 )  ( 7 )  ( 10 )            ← single-select preset chips
  Limits how many events members of this server can create each hour.
```

State mapping:

- Checkbox checked → submitted value is `null`.
- Checkbox unchecked + a chip selected → submitted value is the chip's number.
- On uncheck, a chip is auto-selected so the form is never in an invalid intermediate state: pick the previously saved per-guild value if any, otherwise the configured default (typically 5). The user can then change it.

The "5 / hour" parenthetical on the checkbox is the configured default value. The frontend reads this from a new field on `GET /guild/{id}/settings` (`defaultEventCreateRateLimitPerHour`) so it stays in sync if prod config changes. We do not hardcode `5` in the UI.

### Mobile

- Tabs: horizontal-scroll chip strip (not a `<select>`). Active chip stays visible (`scrollIntoView` on tab change).
- Save bar: full-width sticky, sits above any bottom navigation.

## Data flow

`GET /guild/{id}/settings` response (fields added):

```json
{
  ...existing fields...,
  "eventCreateRateLimitPerHour": null,
  "defaultEventCreateRateLimitPerHour": 5
}
```

`PUT /guild/{id}/settings` request body accepts `eventCreateRateLimitPerHour` (nullable, validated as above). `defaultEventCreateRateLimitPerHour` is read-only and ignored if sent.

## Testing

### Backend

- `EventCreateRateLimiterTest` — extend with three new cases: per-guild override applies, null falls back to config default, `invalidate(guildId)` resets the bucket capacity on subsequent `tryAcquire`.
- A new slice test (or extension of the existing guild-settings test) confirms round-trip read/write of `eventCreateRateLimitPerHour` including the validation rejecting `4` and accepting `null` / `3` / `5` / `7` / `10`.

### Frontend

- Vitest for the rate-limit field's three states: checkbox checked → submits null; checkbox unchecked + chip selected → submits number; toggling checkbox restores the previously selected chip.
- Vitest for tab switching preserving form state across the three tabs.
- Playwright smoke that opens the settings page, switches to *Defaults & limits*, changes the rate limit, saves, and confirms the request body matches.

## Out of scope

- Per-guild Places API limits — those are spend protection, not per-guild policy.
- Audit logging of settings changes.
- Any other migration from global config to per-guild storage.
- Persisting tab state to localStorage (URL hash is enough).

## Risks

- **Stale buckets if `invalidate` is missed.** If a future code path adds another way to update a guild's rate-limit value (e.g. an admin CLI) without calling `invalidate`, the change will not take effect for up to 2h. Document this on `EventCreateRateLimiter` and keep the surface narrow.
- **Database load on the read path.** `tryAcquire` will hit the DB on first request after a cache miss. The existing `GuildRepository` access patterns are already cached upstream, so this should be a non-issue, but worth confirming the lookup is not on the hot path of every event create (it's only invoked once per `createEvent`, which is already DB-heavy).
- **Tab hash conflicting with existing routing.** None expected — the page is `/guild/[id]/settings` with no other hash usage.
