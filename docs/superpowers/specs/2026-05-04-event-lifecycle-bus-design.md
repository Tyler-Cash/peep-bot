# Event Lifecycle Bus

**Status:** Draft
**Date:** 2026-05-04
**Scope:** Replace the hard-coded Spring State Machine + per-state operation wiring with a
durable, listener-based event bus. Migrate every existing state-machine operation onto the
new bus. Trackwork (the motivating use case) is **out of scope** for this spec and will land
on top in a follow-up.

## Background

Today, event lifecycle work in the backend is driven by a Spring State Machine
(`EventStateMachineConfig`) with a fixed sequence of states and one `*Operation` bean per
transition. Adding a new integration (immich today, NSW trackwork next, weather/news later)
requires editing the state machine itself — adding states, transitions, and wiring an
operation into the configurer. This couples unrelated integrations to the core lifecycle
and makes the state machine grow without bound.

`EventLifecyclePoller` re-fires transition signals every minute, providing a coarse retry
mechanism for the entire backbone. Operations rely on this for at-least-once execution.

## Goals

- One uniform extension point for code that reacts to event lifecycle changes.
- At-least-once delivery for every listener, with no per-listener retry plumbing.
- Adding a new integration is "implement an interface, register a bean" — no edits to a
  central config.
- Sequencing between operations is expressed by the events themselves, not by a state
  graph that callers must understand.
- Per-listener observability: "did this side-effect run for this event" answerable with one
  SQL query.

## Non-goals

- Distributed delivery (Kafka/RabbitMQ). Single-instance bot; in-process is sufficient.
  The publisher is a thin abstraction so a broker can replace it later without listener
  changes.
- Removing the `Event.state` column. The state remains as a coarse "where is this event
  in its life" indicator (queryable, used by the UI), but it is no longer a state-machine
  state — it is a derived label updated by listeners.
- Trackwork (covered in a separate spec).
- Cross-listener ordering primitives beyond "listener X emits event Y, listener Z
  subscribes to Y." Saga-style chaining only.

## Architecture

### Event hierarchy

A sealed interface in `event/lifecycle/EventLifecycleEvent.java`. Records carry only
`eventId`; listeners load fresh state from the repo on invocation.

```java
public sealed interface EventLifecycleEvent {
    long eventId();

    // Lifecycle progression (emitted by the listener that completed the prior step)
    record EventCreated(long eventId)           implements EventLifecycleEvent {}
    record EventChannelReady(long eventId)      implements EventLifecycleEvent {}
    record EventRolesReady(long eventId)        implements EventLifecycleEvent {}
    record EventClassified(long eventId)        implements EventLifecycleEvent {}
    record EventPlanned(long eventId)           implements EventLifecycleEvent {}
    record EventPreNotified(long eventId)       implements EventLifecycleEvent {}
    record EventCompleted(long eventId)         implements EventLifecycleEvent {}
    record EventArchived(long eventId)          implements EventLifecycleEvent {}

    // Branching paths
    record EventCancelRequested(long eventId)   implements EventLifecycleEvent {}
    record EventCancelled(long eventId)         implements EventLifecycleEvent {}
    record EventDeleteRequested(long eventId)   implements EventLifecycleEvent {}
    record EventDeleted(long eventId)           implements EventLifecycleEvent {}

    // Time-based (emitted by EventTickScheduler when an event crosses a window boundary)
    record EventPreNotifyDue(long eventId)      implements EventLifecycleEvent {}
    record EventCompletionDue(long eventId)     implements EventLifecycleEvent {}
    record EventArchivalDue(long eventId)       implements EventLifecycleEvent {}
}
```

The sealed hierarchy gives compile-time exhaustiveness when listeners switch on event type.

### Publisher

```java
@Component
public class EventLifecyclePublisher {
    private final ApplicationEventPublisher springPublisher;
    private final ListenerInvocationRepository invocations;
    private final List<DurableEventListener<?>> listeners;
    private final MeterRegistry meterRegistry;

    /** Persists outbox rows for matching durable listeners in the current tx,
     *  then schedules synchronous post-commit dispatch. */
    public void publish(EventLifecycleEvent event) { ... }
}
```

Behavior:

1. For each `DurableEventListener` whose `eventType()` matches `event.getClass()`, INSERT
   a `listener_invocation` row with status `PENDING` (in the caller's transaction).
2. Register a `@TransactionalEventListener(AFTER_COMMIT)` callback that submits each
   matching listener to the executor for synchronous attempt #1. On success → UPDATE
   `SUCCESS`. On failure → UPDATE `FAILED`, increment `attempts`, set `next_retry_at`.

If the publishing transaction rolls back, no rows are inserted — the side-effects are
correctly never owed.

### Listener interface

```java
public interface DurableEventListener<E extends EventLifecycleEvent> {
    /** Stable, human-readable name. Used as the outbox row key AND rendered directly
     *  in logs, dashboards, and the admin UI. Title Case with spaces — e.g.
     *  "Immich Album Prep", "Discord Channel Init". Renaming is a data migration. */
    String name();

    Class<E> eventType();

    /** Must be idempotent. May be invoked multiple times for the same (event, lifecycle
     *  event type) tuple if earlier attempts crashed mid-execution. */
    void handle(E event) throws Exception;
}
```

Convention: `name()` returns a Title Case string with spaces — e.g. `"Immich Album
Prep"`, `"Discord Channel Init"`, `"Pre-Event Notification"`. It is shown verbatim in
logs ("listener 'Immich Album Prep' failed for event 42"), Grafana panels, and the
admin retry UI, so it should read naturally.

### Outbox table

```
listener_invocation
  event_id              bigint     NOT NULL  REFERENCES event(id) ON DELETE CASCADE
  lifecycle_event_type  varchar    NOT NULL  -- 'EventPreNotified', etc.
  listener_name         varchar    NOT NULL  -- 'Immich Album Prep', etc.
  status                varchar    NOT NULL  -- PENDING | SUCCESS | FAILED
  attempts              int        NOT NULL  DEFAULT 0
  last_attempt_at       timestamptz
  next_retry_at         timestamptz
  last_error            text
  created_at            timestamptz NOT NULL DEFAULT now()
  updated_at            timestamptz NOT NULL DEFAULT now()
  PRIMARY KEY (event_id, lifecycle_event_type, listener_name)

  INDEX (status, next_retry_at) WHERE status IN ('PENDING','FAILED')
```

The composite PK gives at-most-once-success semantics per (event, transition, listener):
once a listener succeeds, re-publishing the same lifecycle event will conflict on insert
(handled via `INSERT ... ON CONFLICT DO NOTHING`) and not re-invoke.

### Retry poller

```java
@Component
public class DurableListenerRetryPoller {
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "DurableListenerRetryPoller", lockAtMostFor = "PT5M")
    public void retry() {
        var due = invocations.findDueForRetry(clock.instant(), PAGE_SIZE);
        for (var row : due) {
            invokeWithBackoff(row);
        }
    }
}
```

Backoff schedule: exponential starting at 1 minute, doubling each attempt, capped at
4 hours (1m, 2m, 4m, 8m, 16m, 32m, 1h4m, 2h8m, 4h, 4h, 4h, …). Each retry attempt is
also subject to the per-listener timeout (see below). The retry loop continues
indefinitely — there is no "abandoned" terminal state in v1. Rationale: side-effects
must complete eventually, and a 4h ceiling means at most 6 retries per day per stuck
listener, which is bounded enough to not warrant manual intervention as the default
path. Operator alerting fires on listeners that have been retrying for >24h
(`event.lifecycle.listener.stuck` counter, tagged by `listener_name`); from there the
operator can either fix the underlying issue (the next retry will succeed) or delete
the row to abandon the work explicitly.

### Time-based ticker

```java
@Component
public class EventTickScheduler {
    @Scheduled(cron = "0 * * * * *")  // every minute
    @SchedulerLock(name = "EventTickScheduler", lockAtMostFor = "PT5M")
    public void emitTicks() {
        emitPreNotifyDue();    // event date - 2h window
        emitCompletionDue();   // event date + 1h window
        emitArchivalDue();     // event date + 30d window
    }
}
```

Replaces the time-based guards on existing operations (`PreEventNotifyOperation.guard()`
checks "now is in the 2-hour window before event"). The window check moves to the
scheduler; the listener no longer guards.

A small companion table `event_tick_log (event_id, tick_type, fired_at)` ensures each
tick is emitted at most once per event. ShedLock prevents duplicate emission across
instances; the tick log prevents duplicate emission across restarts.

### Async + error isolation

```java
@Configuration
@EnableAsync
public class EventBusConfig {
    @Bean
    public TaskExecutor eventBusExecutor() {
        var ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("event-bus-");
        return ex;
    }
}
```

The post-commit dispatcher submits each matching listener to this executor. A listener
throwing does not affect any other listener — the outcome is recorded in the outbox row
and the next listener proceeds.

## Listener migration table

Every existing state-machine operation becomes a `DurableEventListener`. Sequencing that
was previously expressed via state transitions is now expressed via the event each
listener emits on completion.

| Existing operation        | New listener (name)             | Subscribes to              | Emits on success         | Sets `Event.state` to |
|---------------------------|---------------------------------|----------------------------|--------------------------|-----------------------|
| `InitChannelOperation`    | `Discord Channel Init`          | `EventCreated`             | `EventChannelReady`      | `INIT_CHANNEL`        |
| `InitRolesOperation`      | `Discord Roles Init`            | `EventChannelReady`        | `EventRolesReady`        | `INIT_ROLES`          |
| `ClassifyOperation`       | `Event Classify`                | `EventRolesReady`          | `EventClassified`        | `CLASSIFY`            |
| `InitCompleteOperation`   | `Event Init Complete`           | `EventClassified`          | `EventPlanned`           | `PLANNED`             |
| `PreEventNotifyOperation` | `Pre-Event Notification`        | `EventPreNotifyDue`        | `EventPreNotified`       | `PRE_NOTIFIED`        |
| `PrepareAlbumOperation`   | `Immich Album Prep`             | `EventPreNotified`         | (none)                   | (no state change)     |
| `CompleteOperation`       | `Event Complete`                | `EventCompletionDue`       | `EventCompleted`         | `POST_COMPLETED`      |
| `PostAlbumOperation`      | `Immich Album Post`             | `EventCompleted`           | (none)                   | (no state change)     |
| `ArchiveOperation`        | `Event Archive`                 | `EventArchivalDue`         | `EventArchived`          | `ARCHIVED`            |
| `CancelOperation`         | `Event Cancel`                  | `EventCancelRequested`     | `EventCancelled`         | `CANCELLED`           |
| `DeleteOperation`         | `Event Delete`                  | `EventDeleteRequested`     | `EventDeleted`           | `DELETED`             |

Setting `Event.state` becomes a side-effect of the listener that owns that step, written
in the same transaction as the listener's outbox row update to `SUCCESS`.

The post-album sub-states (`POST_ALBUM_READY`, `POST_ALBUM_SHARED`) are eliminated.
Album work is a side-effect of `EventPreNotified` / `EventCompleted` and does not own a
lifecycle state — its progress lives in the outbox.

### Trigger sources

- **`EventCreated`** is published by `EventService.create(...)` after the entity is
  inserted, in the same transaction.
- **`EventCancelRequested`** is published by user/admin action (Discord slash command,
  HTTP endpoint).
- **`EventDeleteRequested`** is published when state machine reaches `CANCELLED` or
  `ARCHIVED` and a configurable retention window has elapsed (today this is the
  `DeleteOperation.guard()`; that window check moves to `EventTickScheduler`).
- All time-based events (`EventPreNotifyDue`, `EventCompletionDue`, `EventArchivalDue`)
  come from `EventTickScheduler`.

## Replacing the lifecycle poller

`EventLifecyclePoller` exists today to re-fire transition signals for events that haven't
advanced. Its responsibilities split:

- **Re-trying listener work** → handled by `DurableListenerRetryPoller`.
- **Firing time-based transitions** → handled by `EventTickScheduler`.
- **Catching events stuck in a non-terminal state with no in-flight work** → a new
  `StuckEventDetector` (lower frequency, every 15m) emits a metric and warns. Stuck = no
  outbox row exists for the next-expected lifecycle event after some grace period. This
  is a safety net, not a primary mechanism; the outbox is supposed to be sufficient.

The state machine framework (`@EnableStateMachineFactory`, `EventStateMachineConfig`,
`EventStateMachineService`, `EventStateMachineEvent` enum) is **deleted entirely** at the
end of the migration. The `EventState` enum is retained because it backs `Event.state`.

## Idempotency contract

Every listener must be idempotent for the same (event, lifecycle_event_type) tuple. The
common patterns:

- **External resource creation** (`Immich Album Prep`): "find by external ID, create if
  missing." External ID is derived from the event ID.
- **Discord channel creation** (`Discord Channel Init`): "if `event.channelId` is set,
  no-op; otherwise create and persist."
- **Discord message posting** (`Pre-Event Notification`): the listener checks for an
  existing message ID stored on the event; if present, no-op. (Today this is implicit
  in the once-per-state model; we make it explicit.)
- **State writes**: `UPDATE event SET state = ? WHERE id = ? AND state IN (...allowed
  predecessors...)` — conditional update prevents regressing state on a late retry.

The `DurableEventListener` interface Javadoc states this contract. PR review enforces it.

## Database changes

One Liquibase changeset:

1. `CREATE TABLE listener_invocation` (schema above).
2. `CREATE TABLE event_tick_log (event_id bigint, tick_type varchar, fired_at
   timestamptz, PRIMARY KEY (event_id, tick_type))`.
3. No changes to the `event` table.

Spring State Machine's own JDBC tables (if any are in use) can be dropped at the end of
migration in a separate changeset.

## Rollout plan

Single hard cutover, no parallel-run, no feature flag. Justified by the small steady-
state event population: at any cutover moment every event is either in a terminal
state (`CANCELLED`, `ARCHIVED`, `DELETED`) or in `PLANNED` waiting for its date to
come due. Operator pauses event creation around the deploy so no events sit mid-init
during cutover.

### Pre-deploy

- Verify in prod that no events are in transient states (`CREATED`, `INIT_CHANNEL`,
  `INIT_ROLES`, `CLASSIFY`, `PRE_NOTIFIED`, `POST_ALBUM_READY`, `POST_ALBUM_SHARED`,
  `POST_COMPLETED`). Query: `SELECT state, COUNT(*) FROM event GROUP BY state`. If any
  exist, either let them complete or manually advance/archive them before the deploy.
- Disable event creation: temporarily disable the create-event slash command and
  return 503 from `POST /api/event` for the deploy window.

### Deploy (single PR)

- Add the bus infrastructure: `EventLifecycleEvent`, `EventLifecyclePublisher`,
  `DurableEventListener`, `ListenerInvocationRepository`,
  `DurableListenerRetryPoller`, `EventTickScheduler`, `EventBusConfig`, admin
  endpoints, Liquibase changeset for `listener_invocation` + `event_tick_log`.
- Add a `DurableEventListener` implementation per row of the migration table above,
  body copy-pasted from the corresponding `*Operation`.
- Wire `EventService.create(...)` to publish `EventCreated`.
- Delete `EventStateMachineConfig`, `EventStateMachineService`, `EventLifecyclePoller`,
  `EventStateMachineEvent`, all `*Operation` classes.
- Drop Spring State Machine dependency from `build.gradle`.
- Liquibase: drop Spring State Machine's JDBC tables if any are present; drop
  `POST_ALBUM_READY` and `POST_ALBUM_SHARED` from `EventState` (no in-flight events
  in those states per the pre-deploy check, so no data migration needed).

### Post-deploy

- Re-enable event creation.
- Smoke test: create one event, verify it advances `CREATED → INIT_CHANNEL →
  INIT_ROLES → CLASSIFY → PLANNED` end-to-end and that `listener_invocation` rows for
  each step reach `SUCCESS`.
- Existing `PLANNED` events are picked up naturally — when their date enters the
  pre-notify window, `EventTickScheduler` emits `EventPreNotifyDue` and the listener
  chain proceeds as for any new event. No backfill needed.
- Monitor `event.lifecycle.listener.stuck` for 48h.

## Testing strategy

- **Unit tests per listener.** Each listener gets a test that asserts: (a) handles the
  expected event, (b) emits the expected follow-up event on success, (c) updates
  `Event.state` correctly, (d) is idempotent across two invocations with the same input.
- **Outbox integration test.** With Testcontainers Postgres: publish an event, assert
  rows appear, fail the listener via a stub, assert retry occurs, succeed on second
  attempt, assert row reaches `SUCCESS`.
- **Saga integration test.** Publish `EventCreated`, drive `EventTickScheduler` forward
  in time via `Clock` injection, assert the full chain through `EventArchived`.
- **Existing E2E tests** (`./gradlew e2eTest`) must pass unchanged with the bus enabled
  — they exercise event creation through completion and are the strongest parity check
  against the state-machine implementation.

## Operational concerns

- **Alerting:** Warn on `event.lifecycle.listener.stuck > 0` (any listener invocation
  with `attempts > 24` and `status = FAILED`, sampled hourly). At max backoff (4h),
  24 attempts means roughly 4 days of failure — well past the point where automation
  alone should be silently retrying.
- **Dashboards:** Grafana panel for outbox status counts grouped by listener name (the
  `name()` string is rendered directly). Latency histogram per listener (time from
  publish → SUCCESS).
- **Manual intervention:** Two admin endpoints, both restricted to admin role:
  - `POST /admin/listener-invocations/{id}/retry-now` — resets `next_retry_at = now()`
    so the next poller tick attempts immediately (useful after a fix is deployed).
  - `DELETE /admin/listener-invocations/{id}` — explicitly abandons the work. The row
    is removed; if the underlying lifecycle event is re-published later, the listener
    will be invoked fresh.
- **Data retention:** `listener_invocation` rows for `SUCCESS` invocations on events in
  `DELETED` state can be cleaned up by a periodic job (out of scope for this spec; not
  needed for v1 — table will be small).

## Trade-offs and alternatives considered

**Two-tier design (durable + best-effort).** Considered and rejected. Every current and
foreseeable listener does observable side-effects that warrant at-least-once. The cost
of the second tier — extra interface, two retry mechanisms, decision burden per listener
— is not justified by any concrete use case. If a metrics/audit listener appears later
that genuinely wants fire-and-forget, a `BestEffortEventListener` interface can be added
alongside without disturbing existing code.

**Keep the state machine for the sequenced backbone, only use bus for side-effects.**
Considered and rejected on user request. Maintaining two parallel mechanisms (state
machine + bus) is conceptually heavier than the saga pattern, and the state machine
genuinely is just "do step N+1 after step N" — a chain of events expresses this directly.

**Outbox-only delivery (no in-process attempt).** Considered and rejected. Synchronous
post-commit attempt #1 keeps median latency for healthy paths at single-digit ms.
Outbox-only would make every listener wait for the next retry-poller tick (~30s
expected), regressing user-visible event creation latency for no benefit.

**External broker (Kafka/RabbitMQ).** Considered and rejected for v1. Single-instance
bot. No multi-consumer fan-out outside this JVM. The `EventLifecyclePublisher`
abstraction is deliberately broker-shaped so swapping in a broker later is a one-class
change.

**Spring `@TransactionalEventListener` directly, no custom interface.** Considered and
rejected. We need an outbox row per listener-invocation pair, which requires the
publisher to enumerate matching listeners at publish time. Spring's listener registry
doesn't expose this cleanly. A custom interface gives us the metadata (`name()`,
`eventType()`) we need.

## Resolved decisions

- **Listener `name()`** is explicit, human-readable Title Case (e.g. `"Immich Album
  Prep"`). Validated at startup — `@PostConstruct` scan asserts no duplicates across
  all `DurableEventListener` beans.
- **Per-invocation timeout:** 60 seconds, hard cap, enforced by the dispatcher (the
  invocation runs on a future with `get(60, TimeUnit.SECONDS)`; on timeout the row is
  marked `FAILED` and retried per the backoff schedule). Not overridable per listener
  in v1 — if a listener needs longer, that's a design smell (split the work, or move
  the slow part to a separate listener that subscribes to a `*Done` event).
- **Backoff schedule:** exponential, 1m initial, 2x growth, 4h cap. No abandoned
  terminal state — listener stays `FAILED` and keeps retrying at the cap until either
  it succeeds or an operator deletes the row.

## Out of scope, addressed in follow-up specs

- **NSW trackwork notifications.** A `DurableEventListener<EventChannelReady>` (initial
  pinned post), `DurableEventListener<EventOneWeekOut>`, and
  `DurableEventListener<EventDayOfMorning>` will subscribe to events emitted by
  `EventTickScheduler`. Adds two new `EventLifecycleEvent` records (`EventOneWeekOut`,
  `EventDayOfMorning`) and the corresponding scheduler windows. No changes to the bus
  framework itself. Covered in a follow-up spec.
- **Future integrations** (weather, news, conflict detection) follow the trackwork
  pattern.
