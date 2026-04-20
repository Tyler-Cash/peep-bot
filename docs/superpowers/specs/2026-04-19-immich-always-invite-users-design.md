# Design: Immich Album — Always-Invite Users

**Date:** 2026-04-19  
**Status:** Approved

## Overview

Allow operators to configure a list of Immich users that are automatically invited to every album at creation time,
each with a configurable role (`editor` or `viewer`).

## Configuration

`ImmichConfiguration` gains a new `List<AlbumUser> alwaysInviteUsers` field (defaults to empty list). `AlbumUser` is a
nested record with two fields:

- `userId` — the Immich user UUID (string)
- `role` — `AlbumUserRole` enum with values `EDITOR` and `VIEWER`, serialized as lowercase for the Immich API

Example YAML:

```yaml
dev.tylercash.immich:
  always-invite-users:
    - user-id: "abc123"
      role: editor
    - user-id: "def456"
      role: viewer
```

The field is optional — omitting it (or leaving it empty) is valid and changes no existing behaviour. No changes to
`validate()` are needed.

## ImmichService

New method `addUsersToAlbum(String albumId, List<AlbumUser> users)`:

- Skips immediately if the list is empty or Immich is disabled
- Calls `PUT /api/albums/{albumId}/users` with body `{ "albumUsers": [{ "userId": "...", "role": "editor|viewer" }] }`
- Logs a warning on failure; does not throw — album creation has already committed and is not rolled back

## PrepareAlbumOperation

Immediately after `createAlbum()` succeeds and `immichAlbumId` is set, call:

```java
immichService.addUsersToAlbum(event.getImmichAlbumId(), immichConfiguration.getAlwaysInviteUsers());
```

Failure is non-fatal; the operation proceeds to `createSharedLink()` regardless.

## Error Handling

| Scenario | Behaviour |
|---|---|
| `alwaysInviteUsers` is empty | Skip — no API call made |
| Immich disabled | Skip — consistent with all other `ImmichService` methods |
| `PUT /api/albums/{id}/users` fails | Log warning, continue — album still created and shared link proceeds |

## Files to Create / Modify

| File | Change |
|---|---|
| `immich/ImmichConfiguration.java` | Add `alwaysInviteUsers` list, `AlbumUser` record, `AlbumUserRole` enum |
| `immich/ImmichService.java` | Add `addUsersToAlbum(String albumId, List<AlbumUser> users)` |
| `event/statemachine/operation/PrepareAlbumOperation.java` | Call `addUsersToAlbum()` after album created |
| `immich/ImmichServiceTest.java` | Add tests for `addUsersToAlbum()` |
