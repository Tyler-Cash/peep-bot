# Event State Machine

## States (in ordinal order)

```
PLANNED → NOTIFIED → ALBUM_READY → ALBUM_POSTED → COMPLETED → ARCHIVED → DELETED
```

`isCompleted()` uses `ordinal() >= COMPLETED.ordinal()` (i.e. ordinal 4+), so ALBUM_READY (2) and ALBUM_POSTED (3) are not considered completed.

## Signals

| Signal | Purpose |
|--------|---------|
| PRE_EVENT_NOTIFY | Notify Discord ~2h before event |
| PREPARE_ALBUM | Create Immich album + shared link (no time gate) |
| POST_ALBUM | Post album link to Discord (1h after event) |
| COMPLETE | Lock attendance, remove buttons (6h after event) |
| CANCEL | User-initiated cancel from any active state |
| ARCHIVE | Archive Discord channel (day+1 at 22:00) |
| DELETE | Delete Discord channel (3 months after event) |

## Transition Table

| # | Source | Target | Signal | Guard | Action |
|---|--------|--------|--------|-------|--------|
| 1 | PLANNED | NOTIFIED | PRE_EVENT_NOTIFY | 2h before event & before start | populate attendance, send notification |
| 2 | NOTIFIED | ALBUM_READY | PREPARE_ALBUM | Immich enabled | create album/link, set ALBUM_READY |
| 3 | NOTIFIED | COMPLETED | COMPLETE | 6h after event | remove buttons, lock attendance |
| 4 | ALBUM_READY | ALBUM_POSTED | POST_ALBUM | 1h after event | post share URL to Discord |
| 5 | ALBUM_READY | COMPLETED | COMPLETE | 6h after event | remove buttons, lock attendance |
| 6 | ALBUM_POSTED | COMPLETED | COMPLETE | 6h after event | remove buttons, lock attendance |
| 7 | PLANNED | ARCHIVED | CANCEL | *(none)* | rename, archive, lock attendance |
| 8 | NOTIFIED | ARCHIVED | CANCEL | *(none)* | rename, archive, lock attendance |
| 9 | ALBUM_READY | ARCHIVED | CANCEL | *(none)* | rename, archive, lock attendance |
| 10 | ALBUM_POSTED | ARCHIVED | CANCEL | *(none)* | rename, archive, lock attendance |
| 11 | COMPLETED | ARCHIVED | ARCHIVE | day+1 at 22:00 | archive channel |
| 12 | ARCHIVED | DELETED | DELETE | 3 months after event | delete channel |

## Poller Signal Mapping

```
PLANNED      → [PRE_EVENT_NOTIFY]
NOTIFIED     → [PREPARE_ALBUM, COMPLETE]
ALBUM_READY  → [POST_ALBUM, COMPLETE]
ALBUM_POSTED → [COMPLETE]
COMPLETED    → [ARCHIVE]
ARCHIVED     → [DELETE]
DELETED      → []
```

The poller tries signals in order and stops on the first successful transition.

## Lifecycle Flow Diagram

```
                    ┌─────────┐
                    │ PLANNED │
                    └────┬────┘
                         │ PRE_EVENT_NOTIFY (2h before)
                         ▼
                    ┌──────────┐
               ┌────│ NOTIFIED │────┐
               │    └────┬─────┘    │
               │         │          │ COMPLETE (6h after)
               │         │ PREPARE_ALBUM (Immich enabled)
               │         ▼          │
               │  ┌─────────────┐   │
               │  │ ALBUM_READY │───┤
               │  └──────┬──────┘   │
               │         │          │
               │         │ POST_ALBUM (1h after)
               │         ▼          │
               │  ┌──────────────┐  │
               │  │ ALBUM_POSTED │──┤
               │  └──────────────┘  │
               │                    │
    CANCEL     │         ┌──────────┘
    (any ──────┤         ▼
    active)    │    ┌───────────┐
               │    │ COMPLETED │
               │    └─────┬─────┘
               │          │ ARCHIVE (day+1 22:00)
               ▼          ▼
          ┌──────────┐
          │ ARCHIVED │
          └─────┬────┘
                │ DELETE (3 months)
                ▼
          ┌─────────┐
          │ DELETED │
          └─────────┘
```

## Operation Files

Each operation lives in `backend/.../statemachine/operation/` and contains `guard()` and/or `action()` methods:

| File | Guard | Action | Dependencies |
|------|-------|--------|-------------|
| PreEventNotifyOperation | yes | yes | Clock, DiscordService, EventRepository, ObjectProvider\<EventService\> |
| PrepareAlbumOperation | yes | yes | ImmichConfiguration, ImmichService, EventRepository |
| PostAlbumOperation | yes | yes | Clock, ImmichService, DiscordService, EventRepository |
| CompleteOperation | yes | yes | Clock, DiscordService, EventRepository |
| CancelOperation | no | yes | Clock, DiscordService, EventRepository, ObjectProvider\<EventService\> |
| ArchiveOperation | yes | yes | Clock, DiscordService, EventRepository |
| DeleteOperation | yes | yes | Clock, DiscordService, EventRepository |
