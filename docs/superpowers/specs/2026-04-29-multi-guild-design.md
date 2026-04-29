# Multi-Guild Support — Design

**Status:** Approved (brainstorming complete)
**Date:** 2026-04-29
**Branch:** `feature/multi-guild`

## Goal

Move per-guild configuration out of `application.yaml` and into the database so the bot can serve multiple Discord guilds concurrently. Auto-onboard guilds on bot join. Login is no longer gated on guild membership.

Discord-API-side changes (intents, sharding, install URLs) are tracked separately — see "Follow-up" at the end.

## Decisions

| # | Decision |
|---|---|
| 1 | Auto-onboard on `GuildJoinEvent`; create `guild` row with defaults; run init lazily. |
| 2 | Frontend (existing `GuildSettingsController`) is the editing UX, gated by per-guild `admin_role`. |
| 3 | Login is unconditional — any Discord user can authenticate. Users with no joined guilds see an "Add Peep Bot to your server" CTA in place of the event feed. |
| 4 | Member sync runs for every guild on startup and on join (matches today's behavior, sufficient at hobby scale). |
| 5 | `dev.tylercash.discord.guild-id` is removed entirely. A new `dev.tylercash.contracts.guild-id` is introduced as the sole consumer; contracts continue to use yaml-driven config for now. |
| 6 | Table renamed `guild_settings` → `guild`. The row IS the per-guild config record. |

## Data Model

```
guild
  guild_id            BIGINT     PK
  events_role         VARCHAR    NOT NULL  default 'events'
  admin_role          VARCHAR    NOT NULL  default 'event-admin'
  separator_channel   VARCHAR    NULL
  emoji_accepted      VARCHAR    NOT NULL  default '✅'
  emoji_declined      VARCHAR    NOT NULL  default '❌'
  emoji_maybe         VARCHAR    NOT NULL  default '❓'
  primary_location_place_id  VARCHAR  NULL
  primary_location_name      VARCHAR  NULL
  primary_location_lat       DOUBLE   NULL
  primary_location_lng       DOUBLE   NULL
  joined_at           TIMESTAMP  NOT NULL  default now()
  active              BOOLEAN    NOT NULL  default true
```

Liquibase: rename `guild_settings` → `guild`, add the new columns with defaults baked in via `defaultValue` (no row backfill required — existing rows pick up defaults). `active=false` on `GuildLeaveEvent`; never row-delete (events/contracts may reference the guild historically).

## Lifecycle — `GuildRegistrationService` (new)

Replaces the singleton `DiscordInitializationService.initializeGuild()`.

- **`ApplicationReadyEvent`** — iterate `jda.getGuilds()`. For each: `upsertIfMissing(guildId)`, then `onboard(guild)` which runs the per-guild init (categories, separator channel, member sync). If a row exists with `active=false`, flip back to `active=true`.
- **JDA `GuildJoinEvent`** — `upsertIfMissing` + `onboard`.
- **JDA `GuildLeaveEvent`** — set `active=false`.

`DiscordInitializationService` becomes a stateless per-guild helper invoked by `GuildRegistrationService.onboard(Guild)`.

## Removing `DiscordConfiguration.guildId`

| Call site | Today | After |
|---|---|---|
| `CustomOAuth2UserService` | rejects login if user not in *the* guild | always allows login; populates `discord_user_guild` cache by iterating active guilds |
| `SecurityController.userInfo` | single boolean `isAdmin` against the configured guild | returns `adminGuildIds: long[]`; frontend gates per guild |
| `GuildController.getGuilds` | yaml-guild fallback when cache empty | drop fallback; returns rows from `guild` joined to live `jda.getGuildById` for name/icon/member count |
| `DiscordUserCacheService.fetchAndCache` | configured guild only | iterates active guilds |
| `AvatarController` | configured guild for member lookup | accepts `guildId` query param; falls back to first guild user shares with bot |
| `ContractService` | uses `discordConfig.getGuildId()` | switches to `contractsConfig.getGuildId()` (new yaml field) |
| `DiscordRoleService` lookups | role name from `DiscordConfiguration` | role name from the guild's `guild` row (`events_role` / `admin_role`) |

`DiscordConfiguration.guildId`, `eventsRole`, `adminRole`, `seperatorChannel`, `emoji.*` fields all delete. The class keeps only `token`, `timeout`, plus emoji defaults exposed as constants for the seed.

## Per-guild emoji resolution

The current `DiscordInitializationService.resolveEmojiFields` mutates the singleton `DiscordConfiguration.Emoji` to swap unicode for `<:name:id>` mentions. This breaks under multi-guild — there is no shared emoji namespace.

New: `GuildEmojiResolver`, in-memory `Map<Long, ResolvedEmoji>` keyed by `guildId`. Populated during `onboard(guild)` by reading the `guild` row's `emoji_*` columns and resolving each against `guild.getEmojisByName(name, true)`. Re-resolve on `GuildUpdateEmojisEvent` (or accept stale-until-restart at hobby scale — implementation choice).

Call sites that read `discordConfiguration.getEmoji().getAccepted()` switch to `guildEmojiResolver.forGuild(guildId).accepted()`.

Contract emoji resolution stays as today (singleton `ContractConfiguration.Emoji`, since contracts are single-guild per yaml).

## Settings API + Frontend

Backend:

- `GET /guild/{guildId}/settings` — extend `GuildSettingsDto` with `eventsRole`, `adminRole`, `separatorChannel`, `emojiAccepted`, `emojiDeclined`, `emojiMaybe`. Member-gated (existing).
- `PUT /guild/{guildId}/settings` — accept the new fields. Admin-gated via `discordService.isUserAdminOfServer(guildId, …)`, where the admin-role *name* is now read from the guild's row.

**Lockout escape hatches** (both):

1. Allow Discord guild owner (`Guild.getOwner()`) to bypass the `admin_role` check on this endpoint.
2. Frontend renders a confirm warning before saving: "After this change, only members with role `<new>` will be able to edit guild settings."

Frontend:

- New empty-state on the event feed: when `GET /guild` returns `[]`, show "Add Peep Bot to your server" CTA linking to a Discord bot-install OAuth2 URL (URL composition is environment config, not part of this spec).
- Extend the existing settings page with new fields. Validate emoji as a unicode codepoint OR a custom-emoji name that resolves in the guild (server-side validation — return 400 with the unresolved name).

## Testing

- **Unit:** `GuildRegistrationService` upsert on join, mark-inactive on leave.
- **Integration (Testcontainers):** startup with two mock JDA guilds → two `guild` rows, member sync runs for both, no yaml `guild-id` referenced.
- **API:** non-admin cannot `PUT /guild/{guildId}/settings`; admin of guild A cannot edit guild B; guild owner *can* edit even without `admin_role`.
- **E2E:** logged-in user with zero guilds sees the install CTA, not the event feed.

## Out of scope / Follow-up

A separate spec covers Discord-API-side concerns once the data layer is multi-guild:

- Privileged Gateway Intents (members intent at scale)
- Bot install URL / OAuth2 `bot` scope wiring
- Sharding strategy (only relevant once bot is in many guilds)
- Per-guild rate-limit considerations
- Whether to drop `Guild.loadMembers()` for chunked sync once member counts grow

Filed as: `docs/superpowers/specs/<TBD>-multi-guild-discord-api.md` — author when ready.
