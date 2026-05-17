# Event State Machine

## States (in ordinal order)

```
CREATED → INIT_CHANNEL → INIT_ROLES → PLANNED → PRE_NOTIFIED → POST_ALBUM_READY → POST_ALBUM_SHARED → POST_COMPLETED → ARCHIVED → DELETED
```

`isCompleted()` uses `ordinal() >= POST_COMPLETED.ordinal()` (i.e. ordinal 7+), so POST_ALBUM_READY (5) and POST_ALBUM_SHARED (6) are not considered completed.

## Signals

| Signal | Purpose |
|--------|---------|
| INIT_CHANNEL | Create Discord channel and post event message |
| INIT_ROLES | Create per-event Discord roles (Accepted/Declined/Maybe) |
| INIT_COMPLETE | Create Immich album (if enabled), re-render message |
| PRE_EVENT_NOTIFY | Notify Discord ~2h before event |
| PREPARE_ALBUM | Create Immich album + shared link (no time gate) |
| POST_ALBUM | Post album link to Discord (1h after event) |
| COMPLETE | Lock attendance, remove buttons (6h after event) |
| CANCEL | User-initiated cancel from any active state |
| ARCHIVE | Re-render message, move channel to archive category (10:00 in event zone on completion-date + 2 days; constant for all guilds) |
| DELETE | Delete archived Discord channel and roles (`guild.archive_days` after archive moment for ARCHIVED; 3 months after event for CANCELLED). Event row persists, state → DELETED |

## Transition Table

| # | Source | Target | Signal | Guard | Action |
|---|--------|--------|--------|-------|--------|
| 1 | CREATED | INIT_CHANNEL | INIT_CHANNEL | *(none)* | create channel, post message, save IDs |
| 2 | INIT_CHANNEL | INIT_ROLES | INIT_ROLES | *(none)* | create 3 Discord roles, save role IDs |
| 3 | INIT_ROLES | PLANNED | INIT_COMPLETE | *(none)* | create album (if Immich enabled), re-render message |
| 4 | PLANNED | PRE_NOTIFIED | PRE_EVENT_NOTIFY | 2h before event & before start | populate attendance, send notification |
| 5 | PRE_NOTIFIED | POST_ALBUM_READY | PREPARE_ALBUM | Immich enabled | create album/link, set POST_ALBUM_READY |
| 6 | PRE_NOTIFIED | POST_COMPLETED | COMPLETE | 6h after event | remove buttons, lock attendance |
| 7 | POST_ALBUM_READY | POST_ALBUM_SHARED | POST_ALBUM | 1h after event | post share URL to Discord |
| 8 | POST_ALBUM_READY | POST_COMPLETED | COMPLETE | 6h after event | remove buttons, lock attendance |
| 9 | POST_ALBUM_SHARED | POST_COMPLETED | COMPLETE | 6h after event | remove buttons, lock attendance |
| 10 | CREATED | ARCHIVED | CANCEL | *(none)* | conditional cleanup, lock attendance |
| 11 | INIT_CHANNEL | ARCHIVED | CANCEL | *(none)* | conditional cleanup, lock attendance |
| 12 | INIT_ROLES | ARCHIVED | CANCEL | *(none)* | conditional cleanup, lock attendance |
| 13 | PLANNED | ARCHIVED | CANCEL | *(none)* | rename, archive, lock attendance |
| 14 | PRE_NOTIFIED | ARCHIVED | CANCEL | *(none)* | rename, archive, lock attendance |
| 15 | POST_ALBUM_READY | ARCHIVED | CANCEL | *(none)* | rename, archive, lock attendance |
| 16 | POST_ALBUM_SHARED | ARCHIVED | CANCEL | *(none)* | rename, archive, lock attendance |
| 17 | POST_COMPLETED | ARCHIVED | ARCHIVE | 10:00 (event zone) on completion-date + 2 days | re-render message, archive channel |
| 18 | ARCHIVED | DELETED | DELETE | archive moment + `guild.archive_days` | delete roles + archived channel (event row persists) |
| 19 | CANCELLED | DELETED | DELETE | 3 months after event | delete roles + channel (event row persists) |

## Poller Signal Mapping

```
CREATED          → [INIT_CHANNEL]
INIT_CHANNEL     → [INIT_ROLES]
INIT_ROLES       → [INIT_COMPLETE]
PLANNED          → [PRE_EVENT_NOTIFY]
PRE_NOTIFIED     → [PREPARE_ALBUM, COMPLETE]
POST_ALBUM_READY → [POST_ALBUM, COMPLETE]
POST_ALBUM_SHARED→ [COMPLETE]
POST_COMPLETED   → [ARCHIVE]
ARCHIVED         → [DELETE]
DELETED          → []
```

The poller tries signals in order and stops on the first successful transition.

## Lifecycle Flow Diagram

```
               ┌─────────┐
               │ CREATED │
               └────┬────┘
                    │ INIT_CHANNEL
                    ▼
            ┌──────────────┐
            │ INIT_CHANNEL │
            └──────┬───────┘
                   │ INIT_ROLES
                   ▼
            ┌─────────────┐
            │ INIT_ROLES  │
            └──────┬──────┘
                   │ INIT_COMPLETE
                   ▼
              ┌─────────┐
              │ PLANNED │
              └────┬────┘
                   │ PRE_EVENT_NOTIFY (2h before)
                   ▼
            ┌──────────────┐
       ┌────│ PRE_NOTIFIED │────┐
       │    └──────┬───────┘    │
       │           │            │ COMPLETE (6h after)
       │           │ PREPARE_ALBUM (Immich enabled)
       │           ▼            │
       │  ┌────────────────┐    │
       │  │POST_ALBUM_READY│────┤
       │  └───────┬────────┘    │
       │          │             │
       │          │ POST_ALBUM (1h after)
       │          ▼             │
       │  ┌─────────────────┐   │
       │  │POST_ALBUM_SHARED│───┤
       │  └─────────────────┘   │
       │                        │
CANCEL │           ┌────────────┘
(any ──┤           ▼
active)│    ┌────────────────┐
       │    │ POST_COMPLETED │
       │    └───────┬────────┘
       │            │ ARCHIVE (completion-date+2 @ 10:00)
       ▼            ▼
    ┌──────────┐
    │ ARCHIVED │
    └─────┬────┘
          │ DELETE (archive moment + archive_days)
          ▼
    ┌─────────┐
    │ DELETED │
    └─────────┘
```

## Discord Roles

Each event gets three Discord roles created during the `INIT_ROLES` phase:
- `{EventName} - Accepted`
- `{EventName} - Declined`
- `{EventName} - Maybe`

Event names are truncated to 89 characters to stay within Discord's 100-character role name limit.

When a user clicks an attendance button, their event roles are updated to match their status. If they toggle off (REMOVED), all event roles are removed.

Roles and the archived Discord channel are deleted during the `DELETE` transition (3 months after event). The event row and attendance records persist; the event's state becomes `DELETED`.

## Operation Files

Each operation lives in `backend/.../statemachine/operation/` and contains `guard()` and/or `action()` methods:

| File | Guard | Action | Dependencies |
|------|-------|--------|-------------|
| InitChannelOperation | no | yes | DiscordService, EventRepository |
| InitRolesOperation | no | yes | DiscordService, EventRepository |
| InitCompleteOperation | no | yes | ImmichService, DiscordService, EventRepository |
| PreEventNotifyOperation | yes | yes | Clock, DiscordService, EventRepository, ObjectProvider\<EventService\> |
| PrepareAlbumOperation | yes | yes | ImmichConfiguration, ImmichService, EventRepository |
| PostAlbumOperation | yes | yes | Clock, ImmichService, DiscordService, EventRepository |
| CompleteOperation | yes | yes | Clock, DiscordService, EventRepository |
| CancelOperation | no | yes | Clock, DiscordService, EventRepository, ObjectProvider\<EventService\> |
| ArchiveOperation | yes | yes | Clock, DiscordService, EventRepository, ObjectProvider\<EventService\> |
| DeleteOperation | yes | yes | Clock, DiscordService, EventRepository |
