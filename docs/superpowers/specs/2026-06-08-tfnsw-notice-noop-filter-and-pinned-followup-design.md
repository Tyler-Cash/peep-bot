# TfNSW notice: drop no-op alerts, fix stale dates, 3-day pinned follow-up

**Date:** 2026-06-08
**Status:** Approved

## Problem

TfNSW transport notices posted to Discord include a trailing "Disruption" section of
meaningless no-op items, e.g.:

```
 Disruption — Fri 15 May, 3PM–4:46PM (2 alerts):
  ↳ Airport & South Line — Station Update - Central (details)
  ↳ Airport & South Line — Station Update - Central (details)
```

for an event on 13–14 June. Three defects combine to produce this:

1. **Station rules ignore disruption.** `TfnswNoteworthyFilter`'s `NEAREST_STATION` and
   `MAJOR_STATION` rules match on location alone. A purely informational "Station Update –
   Central" alert qualifies simply because Central is a major station. Only the
   `CITYWIDE_LINE` rule checks `effect().isDisruptive()`.
2. **Open-ended alerts overlap every event.** These alerts carry a start (15 May) and no
   real end, so `TfnswAlertsClient` defaults the end to `now + 365d` (`FAR_FUTURE_SECONDS`).
   An open-ended window overlaps any event date, however far out, so `overlapsAnyPeriod`
   always returns true.
3. **Stale-date rendering.** `TfnswReportingService.clusterTimeWindow` only ever adds the
   *start* date to its date set, so the synthetic far-future end date is invisible and only
   the stale "15 May" shows. The "4:46PM" is just the time-of-day of the synthetic
   `now + 365d` end — pure noise.

The empty "(details)" link is the GTFS `url` field, which informational alerts leave blank
or generic. (Blank URLs are already dropped today; the offending items carried a useless but
non-blank URL.)

Separately, the follow-up re-check currently runs **7 days** before the event and the notice
is **never pinned**.

## Goals

1. Stop posting informational / no-op alerts.
2. Render honest date windows for the alerts we do keep.
3. Move the follow-up from 7 days to **3 days** before the event.
4. **Pin** the notice — at creation and at the 3-day follow-up.

Creation-time posting "if relevant" already works (`TfnswEventCreatedListener` runs the full
filter+post on `EventCreated`); no change needed there beyond pinning.

## Design

### 1. Filter — `TfnswNoteworthyFilter`

**Disruption gate on station rules.** Introduce:

```
disruptiveEnough(alert) = alert.effect().isDisruptive()
                       || hasStrongDisruptionLanguage(alert.headline())
```

`hasStrongDisruptionLanguage` reuses the strong-language keyword set already encoded as the
keep-signal in `isCosmeticHeadline` ("buses replace", "do not run", "closed", "suspended",
" only", "between ", "cancelled", "delays", "no service", "start and end"). Extract that set
into a shared helper used by both methods. `NEAREST_STATION` and `MAJOR_STATION` now require
`disruptiveEnough`. `CITYWIDE_LINE` is unchanged (already requires `isDisruptive()`).

**Stale-standing guard.** With the disruption gate above, a *non*-disruptive open-ended alert
is already dropped on content, so a guard conditioned on "non-disruptive + open-ended" would be
redundant dead code. To give the open-ended suppression independent, safe value it is instead a
**stale-standing** rule applied at the overlap step: an alert is dropped when *every* overlapping
period is both open-ended (ends ≥ `standingHorizonDays` after the event — i.e. carries no
genuine end, e.g. the `now + 365d` default) **and** started more than `standingHorizonDays`
before the event. That is the fingerprint of a never-closed notice. It fires regardless of
effect, so even a disruptive-tagged but months-stale standing alert is dropped, while a real
bounded closure (real end) and a *recent* indefinite disruption are both kept.

New config on `TfnswConfiguration`: `standingHorizonDays` (default 90).

### 2. Display — `TfnswReportingService.clusterTimeWindow`

The reported "Fri 15 May, 3PM–4:46PM" had two parts: the date was the (correct) start, but the
"4:46PM" was the time-of-day of the synthetic far-future end. The date set is intentionally
labelled by **start** night (overnight trackwork reads as the night it begins, not the next
morning), so the fix is narrow: skip open-ended ends (period longer than 180 days) from the
end-of-window time. When all of a cluster's ends are open-ended the banner renders
"… from 9:40PM" instead of inventing a synthetic end time.

### 3. Follow-up timing — replace 7 with 3

- Rename `TfnswWeekBeforePoller` → `TfnswFollowUpPoller`.
- Add config `dev.tylercash.tfnsw.follow-up-lead-days` (default **3**).
- Query offset `plusDays(7)` → `plusDays(followUpLeadDays)`.
- Rename repository method `findEventIdsForWeekBeforeCheck` → `findEventIdsForFollowUpCheck`.
- Daily 06:00 Australia/Sydney cron unchanged.

### 4. Pinning — at create and at the 3-day pass

- Add `DiscordService.pinMessageInEventChannel(Event, long messageId)`: retrieve by id (as
  `replyToMessage` does) then call the existing `pinSilently` (honors `PIN_MESSAGES`, silent
  on failure, async queue). Missing message → silent no-op.
- In `TfnswOrchestrator`:
  - **Creation path** (first post): pin the new message immediately after posting.
  - **Follow-up path** (`isWeekBeforeRecheck == true`): after handling any deltas, pin the
    anchor (`originalMessageId`) **even when there are no new items**, so a notice posted
    weeks earlier still gets pinned 3 days out. Re-pinning is idempotent.

The event embed is already pinned by `postEventMessage`; the notice becomes a second pin,
well under Discord's 50-pin cap.

### 5. Empty "(details)" links

No code change. Blank URLs are already dropped; the items that carried useless links are the
informational ones removed by (1). Confirm via tests rather than add link-scrubbing.

## Tradeoffs

- The strong-language gate keys off headline wording, so a genuine disruption with an `OTHER`
  effect and bland headline could be dropped. Judged a rare, acceptable miss versus constant
  noise.
- The stale-standing guard fires regardless of effect, so a genuinely disruptive but
  months-old never-closed alert is dropped. That is the intended behaviour — such an alert is
  almost always a TfNSW data artifact — and recent indefinite disruptions are still kept.

## Testing

- `TfnswNoteworthyFilter`: informational station alert (non-disruptive effect, soft headline)
  → dropped; disruptive or strong-language station alert → kept; open-ended non-disruptive →
  dropped; bounded disruptive long closure → kept.
- `clusterTimeWindow`: bounded multi-day window renders both ends; far-future sentinel never
  printed.
- `TfnswFollowUpPoller`: query offset honors `follow-up-lead-days` (3).
- `TfnswOrchestrator` / `DiscordService`: pins on create and on follow-up (including the
  no-new-items case); silent on missing message / missing permission.
