# TfNSW Disruption Posts — Design

**Date:** 2026-05-10
**Status:** Draft (awaiting implementation plan)

## Summary

Per-guild toggleable feature that posts noteworthy Sydney public-transport disruptions and major-traffic events to a Discord event's channel. Fires on event creation and re-checks 7 days before the event date, posting only when noteworthy info changes.

## Problem

Trackwork on Sydney Trains/Metro is near-constant — most weekends have *something*. Posting every alert would be noise. The goal: surface only impacts that actually affect attendees getting to a specific event (nearest station closed, major interchange affected, severe city-wide disruption, major road closure or major event near the venue).

## Goals

- Per-guild toggle (matches `immichEnabled`, `googleAutocompleteEnabled`, `rewindEnabled`, `contractsEnabled` pattern)
- Post a single embed in the event's Discord channel/thread on event creation if anything noteworthy applies to the event date
- Re-check 7 days before event; post an "Update:" embed only if alerts have *materially changed*
- Cover both **public transport** (rail/metro) and **traffic** (closures + major events)
- Degrade gracefully on TfNSW outage — never block event creation

## Non-Goals (v1)

- Bus-specific alerts (other than rail trip-replacement context already in the rail feed)
- Ferry alerts
- Roadworks feed
- User-configurable major-station list (curated constant; can move to DB later)
- Posting to a separate channel — always replies in event thread
- Routing/turn-by-turn impact analysis

## Data Sources

All from **TfNSW Open Data Hub** (opendata.transport.nsw.gov.au), single API key.

| Feed | Format | Use |
|------|--------|-----|
| Sydney Trains GTFS-Realtime Service Alerts | GTFS-R protobuf | Live rail incidents, severity, affected stops/routes |
| Sydney Metro GTFS-R Service Alerts | GTFS-R protobuf | Metro disruptions |
| Trip Replacements (planned trackwork) | GTFS-R protobuf | Scheduled weekend works |
| Live Traffic NSW — Major Events | GeoJSON | Curated major events with road impact (Vivid, NRL/AFL match days, marathons, concerts) |
| Live Traffic NSW — Hazards/Closures | GeoJSON | Road closures on classified roads |

## Filter Logic ("Noteworthy")

### Rail / Metro
Post if **any** of:

1. **Nearest-station hit** — the nearest train/metro station to the event venue is in `affected_entity` of an active alert covering event date
2. **Major-station allowlist** — alert affects any station in the curated `MajorStations` list (Central, Town Hall, Wynyard, Circular Quay, Martin Place, Chatswood, Parramatta, North Sydney, Bondi Junction, Strathfield, Hurstville, all Sydney Metro stations)
3. **City-wide severe** — alert is `SEVERITY_SEVERE` and affects a Sydney Trains line or Metro line, regardless of stations

### Traffic
Post if **any** of:

1. **Major Events** — any Live Traffic Major Event whose geometry is within ~5 km Haversine of the event venue, with time window overlapping event day. No further filtering — TfNSW already curates this list.
2. **Road closures** — Hazards feed entries where `mainCategory == "roadClosure"` (not lane closures, not crashes), `roads[].classOfRoad` is motorway/arterial/sub-arterial, geometry within ~2 km of venue, active during event time

Roadworks feed is ignored.

### Combined Output
A single Discord embed with two sections (`🚆 Transport`, `🚗 Traffic`) — never multiple posts in one trigger.

## Architecture

### New Package: `dev.tylercash.event.tfnsw`

| Class | Responsibility |
|-------|----------------|
| `TfnswConfiguration` | Spring config — registers WebClients (alerts API + live traffic API) with API key from `dev.tylercash.tfnsw.api-key`. Mirrors `ImmichConfiguration` / `PlacesConfiguration`. |
| `TfnswAlertsClient` | Fetches GTFS-R Service Alerts (rail + metro) and Trip Replacements. Parses protobuf. |
| `LiveTrafficClient` | Fetches Major Events + Hazards GeoJSON feeds. |
| `TfnswNoteworthyFilter` | Pure function: `(alerts, trafficEvents, eventCoords, eventTime) → List<NoteworthyItem>`. Implements the rules above. |
| `MajorStations` | Constant — curated allowlist of major station names/IDs. |
| `TfnswReportingService` | Composes Discord embed (Transport + Traffic sections) with deep-link to `transportnsw.info/alerts/...`. |
| `TfnswEventCreatedListener` | Lifecycle listener — fires on event creation, runs the flow. Mirrors `ImmichAlbumPrepListener`. |
| `TfnswWeekBeforePoller` | `@Scheduled` daily cron (06:00 Australia/Sydney) + `@SchedulerLock`. Finds events 7 days out and re-runs the flow. Registered in `AdminJobCatalog`. |

### Persistence

**New table** `tfnsw_event_snapshot` (Liquibase changeset):
- `event_id` PK FK → `event`
- `alert_ids_hash` varchar — SHA-256 of sorted active noteworthy alert IDs (rail + traffic combined)
- `last_posted_at` timestamptz — when an embed was actually sent (null = checked but nothing noteworthy)
- `created_at` / `updated_at` timestamptz

The 7-day-before re-check posts only if the new hash differs **and** there's at least one new noteworthy item not in the previous snapshot. Mere disappearance of an alert doesn't trigger a re-post.

**New columns on `event`** (Liquibase):
- `location_lat double precision` (nullable)
- `location_lng double precision` (nullable)

Resolved via Google Places Details on first need (using existing `locationPlaceId`), then cached on the row. Fallback to `guild.primary_location_lat/lng` when no place_id.

**New column on `guild`** (Liquibase): `tfnsw_enabled boolean not null default false`.

### Frontend / Admin Surface

- `Guild.java` — add `tfnswEnabled` field; `Guild.withDefaults()` sets `false`
- `AdminFeaturesRequest` — add `Boolean tfnswEnabled`
- `AdminController` — wire through to guild update
- `AdminGuildDto` — expose flag
- `GuildFeaturesController` — expose for non-admin read paths if relevant
- Frontend bot-admin panel — add a toggle row matching the Immich/contracts pattern

### Resilience

- Resilience4j circuit breaker on both clients (mirrors `DiscordCircuitBreaker`)
- Caffeine cache: alerts feed cached ~5 min, traffic feeds ~5 min (upstream refresh ≥ 1 min, this avoids hammering during cron sweep across many events)
- Timeouts: 10s per upstream call
- On 5xx / timeout: log + skip the post, don't fail the lifecycle event
- API key in `application-local.yaml` and env var `TFNSW_API_KEY` for prod

## Data Flow

```
Event created  ──► TfnswEventCreatedListener
                    ├─ guild.tfnswEnabled? no → return
                    ├─ resolve event coords:
                    │    if event.locationLat/Lng null and event.locationPlaceId set →
                    │       Places Details → persist on Event
                    │    else if no place_id → use guild.primary_location_*
                    │    else if no fallback → log + return (can't filter geo)
                    ├─ fetch in parallel:
                    │    TfnswAlertsClient.fetchRailAndMetro()
                    │    TfnswAlertsClient.fetchTripReplacements()
                    │    LiveTrafficClient.fetchMajorEvents()
                    │    LiveTrafficClient.fetchHazards()
                    ├─ TfnswNoteworthyFilter.filter(...)  → NoteworthyItem[]
                    ├─ if items non-empty → TfnswReportingService.post(event, items)
                    └─ persist TfnswEventSnapshot(eventId, hash(items), postedAt or null)

Daily cron 06:00 Australia/Sydney (TfnswWeekBeforePoller, @SchedulerLock)
  └─ for each event WHERE date BETWEEN now+7d AND now+8d AND guild.tfnswEnabled:
       same fetch + filter
       compare new hash vs snapshot.alertIdsHash
       if hash changed AND newItems \ oldItems is non-empty:
         post "Update:" embed, update snapshot
```

## Configuration Properties

| Property | Default | Purpose |
|----------|---------|---------|
| `dev.tylercash.tfnsw.api-key` | *(required when feature enabled in any guild)* | TfNSW Open Data API key |
| `dev.tylercash.tfnsw.alerts-base-url` | `https://api.transport.nsw.gov.au/v1/gtfs/alerts` | Override for tests |
| `dev.tylercash.tfnsw.live-traffic-base-url` | `https://api.transport.nsw.gov.au/v2/live/hazards` | Override for tests |
| `dev.tylercash.tfnsw.nearest-station-radius-km` | `1.5` | Threshold for "nearest station" match |
| `dev.tylercash.tfnsw.major-event-radius-km` | `5.0` | Major-event proximity radius |
| `dev.tylercash.tfnsw.closure-radius-km` | `2.0` | Road-closure proximity radius |

## Testing

- **Unit** — `TfnswNoteworthyFilter` with fixture GTFS-R + GeoJSON payloads covering each rule (nearest hit, major-station hit, severe city-wide, major-event-near, closure-near, all negatives). Pure function → easy to exhaustively cover.
- **Unit** — `MajorStations` allowlist contents pinned by snapshot test (so additions are intentional).
- **Integration** — `TfnswEventCreatedListener` extends `AbstractHttpIntegrationTest`, uses WireMock to stub TfNSW endpoints. Use `fixtures.seedEvent(...)`. Generate unique IDs via `TestIds.nextLong()`.
- **Integration** — `TfnswWeekBeforePoller` test uses `SharedPostgres.registerIsolatedDatabase` (it scans global state). Verifies hash-diff suppression: same alerts → no second post; new alert → second post.
- **Resilience** — verify event creation succeeds when TfNSW returns 500.

## Migration / Rollout

1. Liquibase changesets for `guild.tfnsw_enabled`, `event.location_lat/lng`, `tfnsw_event_snapshot` table
2. Default `tfnsw_enabled = false` for all existing guilds — feature opts in via admin panel
3. No backfill of `event.location_lat/lng` — populated lazily on next interaction (or on first TfNSW listener run)

## Open Questions

None blocking. The following are deliberate v1 trade-offs to revisit if usage shows demand:
- Should we batch multiple noteworthy alerts into one weekly digest instead of posting at create-time? (No — losing context with the event creation moment)
- Should the major-station list be per-guild configurable? (No — defer)

## Files to Create / Modify

**Create:**
- `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswConfiguration.java`
- `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswAlertsClient.java`
- `backend/src/main/java/dev/tylercash/event/tfnsw/LiveTrafficClient.java`
- `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswNoteworthyFilter.java`
- `backend/src/main/java/dev/tylercash/event/tfnsw/MajorStations.java`
- `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswReportingService.java`
- `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswEventSnapshot.java` (entity) + repository
- `backend/src/main/java/dev/tylercash/event/lifecycle/listener/TfnswEventCreatedListener.java`
- `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswWeekBeforePoller.java`
- Liquibase changeset(s) for `guild.tfnsw_enabled`, `event.location_lat/lng`, `tfnsw_event_snapshot`
- Tests (filter unit, listener integration, poller integration)

**Modify:**
- `backend/src/main/java/dev/tylercash/event/discord/Guild.java` — add `tfnswEnabled` + default
- `backend/src/main/java/dev/tylercash/event/event/model/Event.java` — add `locationLat`/`locationLng`
- `backend/src/main/java/dev/tylercash/event/admin/AdminFeaturesRequest.java` — add field
- `backend/src/main/java/dev/tylercash/event/admin/AdminController.java` — wire field through
- `backend/src/main/java/dev/tylercash/event/admin/AdminGuildDto.java` — expose
- `backend/src/main/java/dev/tylercash/event/admin/AdminJobCatalog.java` — register poller
- `backend/src/main/resources/application.yaml` — defaults for radii + base URLs
- `frontend/` bot-admin features panel — add TfNSW toggle row
- `CLAUDE.md` — note new per-guild flag and table
