# Design: Automatic Immich Photo/Video Upload from Discord Event Channels

**Date:** 2026-04-15  
**Status:** Approved

## Overview

Automatically upload any photo or video posted in a Discord event channel (after the event's start time) to the event's
corresponding Immich album. Leverages the existing Immich integration (albums and shared links are already created per
event) and the existing JDA listener pattern.

## Architecture

### New Components

**`MessageReceivedListener`** (`discord/listener/MessageReceivedListener.java`)

A new `ListenerAdapter` registered with JDA alongside `ButtonInteractionListener` and `ModalInteractionListener`.
Handles `onMessageReceived` events.

Filtering logic (skip if any condition is true):
1. Immich is disabled (`immichConfiguration.isEnabled()` is false)
2. No event found for the message's channel ID in `EventRepository`
3. Event start datetime has not yet passed (`event.getDateTime().isAfter(ZonedDateTime.now(clock))`)
4. Event has no `immichAlbumId` (album not yet created — transient window at event start, < 1 min)
5. Message has no attachments

For each attachment that passes filtering:
- Check content type is an image or video (`image/*` or `video/*`)
- Stream from Discord CDN via `Attachment.getProxy().download()`
- Delegate to `ImmichService.uploadAsset(...)`

After all attachments are processed, call `ImmichService.addAssetsToAlbum(albumId, assetIds)` once per message
(batched, not per-attachment).

### ImmichService Additions

**`uploadAsset(String filename, InputStream data, String contentType) → Optional<String>`**

- Calls `POST /api/assets` with `multipart/form-data`
- Returns the Immich asset ID on success, `Optional.empty()` on failure
- Wraps HTTP call with the `immichUploadRetry` Retry bean

**`addAssetsToAlbum(String albumId, List<String> assetIds)`**

- Calls `PUT /api/albums/{albumId}/assets`
- Wraps HTTP call with the `immichUploadRetry` Retry bean
- Logs a warning if the call fails after all retries (assets remain in Immich library, just not in album)

### Retry Configuration

A new `Retry` bean (`immichUploadRetry`) added to `ServiceConfiguration`:

- **Max attempts:** 3
- **Backoff:** exponential, starting at 1 second, multiplier 2x (waits: 1s → 2s → give up)
- **Retry on:** `RestClientException` excluding `HttpClientErrorException` (4xx errors are permanent failures,
  not retried)
- **On exhaustion:** logs an error, continues with remaining attachments

Retries are blocking on the JDA listener thread. This is acceptable — uploads are already blocking I/O operations with
no user-facing latency requirement.

## Data Flow

```
Discord message with attachments
        │
        ▼
MessageReceivedListener.onMessageReceived()
        │
        ├─ Lookup event by channelId
        ├─ Guard checks (enabled, started, albumId present, has attachments)
        │
        ▼
For each image/video attachment:
        │
        ├─ Attachment.getProxy().download() → InputStream
        ├─ ImmichService.uploadAsset(filename, stream, contentType)
        │       └─ POST /api/assets (multipart) [with retry]
        │              └─ returns assetId
        │
        ▼
ImmichService.addAssetsToAlbum(albumId, [assetIds])
        └─ PUT /api/albums/{albumId}/assets [with retry]
```

## Error Handling

| Failure scenario | Behaviour |
|---|---|
| Single asset upload fails after retries | Log error, skip that attachment, continue with others |
| `addAssetsToAlbum` fails after retries | Log warning, assets remain in Immich library (not in album) |
| Event has no `immichAlbumId` at message time | Skip silently — transient gap at event start, resolves within one poll cycle |
| Immich disabled | Skip all processing, no-op |
| Non-image/video attachment | Skip silently |

## Edge Cases

- **Bot downtime:** Photos posted while the bot is offline are not uploaded (no retry/backfill). The album link
  is already shared in the channel so users can upload manually.
- **Race at event start:** The `immichAlbumId` is set by the lifecycle poller (runs every 60s). Photos posted
  in the first ~60s after event start may be skipped. Acceptable trade-off given the simplicity of the listener approach.
- **Duplicate uploads:** No deduplication. If a user posts the same file twice, it uploads twice. Immich handles
  duplicate detection at the server level for identical files.

## Configuration

No new configuration properties. Uses existing:
- `dev.tylercash.immich.enabled` — gates all Immich functionality
- `dev.tylercash.immich.base-url` and `dev.tylercash.immich.api-key` — already used by `ImmichService`

## Files to Create / Modify

| File | Change |
|---|---|
| `discord/listener/MessageReceivedListener.java` | **Create** — new JDA listener |
| `immich/ImmichService.java` | **Modify** — add `uploadAsset` and `addAssetsToAlbum` |
| `global/ServiceConfiguration.java` | **Modify** — add `immichUploadRetry` Retry bean |
| `discord/ClientConfiguration.java` | **Modify** — register new listener with JDA |
