# Event Lifecycle Bus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Spring State Machine + per-state operation wiring with a durable, listener-based event bus. Migrate every existing state-machine operation onto the new bus in a single hard cutover.

**Architecture:** A sealed `EventLifecycleEvent` hierarchy is published via `EventLifecyclePublisher`, which inserts an outbox row (`listener_invocation`) per matching `DurableEventListener` in the caller's transaction, then dispatches synchronously after commit. `DurableListenerRetryPoller` retries failed rows with exponential backoff (1m → 4h cap). `EventTickScheduler` emits time-based events (`EventPreNotifyDue`, `EventCompletionDue`, `EventArchivalDue`) using an `event_tick_log` table for at-most-once-per-event semantics. Each existing `*Operation` becomes a `DurableEventListener` that subscribes to one event, does its work, sets `Event.state`, and emits the next event in the chain (saga pattern).

**Tech Stack:** Spring Boot 3.5.8, Java 21, Spring `ApplicationEventPublisher`, ShedLock, Liquibase, Testcontainers Postgres, JUnit 5, Mockito.

**Spec:** [`docs/superpowers/specs/2026-05-04-event-lifecycle-bus-design.md`](../specs/2026-05-04-event-lifecycle-bus-design.md)

---

## File Structure

### New files

| Path | Responsibility |
|---|---|
| `backend/src/main/java/dev/tylercash/event/lifecycle/EventLifecycleEvent.java` | Sealed event hierarchy + records |
| `backend/src/main/java/dev/tylercash/event/lifecycle/DurableEventListener.java` | Listener interface |
| `backend/src/main/java/dev/tylercash/event/lifecycle/EventLifecyclePublisher.java` | Publisher: outbox insert + post-commit dispatch |
| `backend/src/main/java/dev/tylercash/event/lifecycle/EventBusConfig.java` | Executor + multicaster + name uniqueness validator |
| `backend/src/main/java/dev/tylercash/event/lifecycle/ListenerInvocation.java` | JPA entity for outbox row |
| `backend/src/main/java/dev/tylercash/event/lifecycle/ListenerInvocationId.java` | Composite key |
| `backend/src/main/java/dev/tylercash/event/lifecycle/ListenerInvocationRepository.java` | Spring Data JPA repo |
| `backend/src/main/java/dev/tylercash/event/lifecycle/DurableListenerRetryPoller.java` | Scheduled retry loop |
| `backend/src/main/java/dev/tylercash/event/lifecycle/EventTickScheduler.java` | Time-based event emitter |
| `backend/src/main/java/dev/tylercash/event/lifecycle/EventTickLog.java` + `Repository` | At-most-once-per-event tick tracking |
| `backend/src/main/java/dev/tylercash/event/lifecycle/admin/ListenerInvocationAdminController.java` | Admin retry-now / delete endpoints |
| `backend/src/main/java/dev/tylercash/event/lifecycle/listener/*Listener.java` | One file per migrated operation (11 listeners) |
| `backend/src/test/java/dev/tylercash/event/lifecycle/**/*Test.java` | Unit + integration tests |

### Modified files

| Path | Change |
|---|---|
| `backend/src/main/java/dev/tylercash/event/event/EventService.java` | Inject `EventLifecyclePublisher`; on create, publish `EventCreated` |
| `backend/src/main/java/dev/tylercash/event/event/EventController.java` | Add 503 short-circuit when creation paused (config flag) |
| `backend/src/main/java/dev/tylercash/event/discord/listener/*` | Discord cancel command publishes `EventCancelRequested` |
| `backend/src/main/resources/db/changelog/db.changelog-master.yaml` | Append changesets (tables + drop unused EventState constants at the end) |
| `backend/build.gradle` | Remove `spring-statemachine-starter` dependency at end |

### Deleted files (final cutover)

- `backend/src/main/java/dev/tylercash/event/event/statemachine/EventStateMachineConfig.java`
- `backend/src/main/java/dev/tylercash/event/event/statemachine/EventStateMachineService.java`
- `backend/src/main/java/dev/tylercash/event/event/statemachine/EventStateMachineEvent.java`
- `backend/src/main/java/dev/tylercash/event/event/statemachine/EventLifecyclePoller.java`
- `backend/src/main/java/dev/tylercash/event/event/statemachine/operation/*.java` (all 11)
- All tests under `backend/src/test/java/dev/tylercash/event/event/statemachine/**`

---

## Phase 0: Pre-flight

### Task 0: Confirm clean baseline

- [ ] **Step 1: Confirm tests pass on current main**

```bash
cd backend && ./gradlew spotlessCheck test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Create feature branch**

```bash
git checkout -b feat/event-lifecycle-bus
```

---

## Phase 1: Bus framework (no behavior change yet)

### Task 1: Define `EventLifecycleEvent` sealed hierarchy

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/EventLifecycleEvent.java`
- Test: `backend/src/test/java/dev/tylercash/event/lifecycle/EventLifecycleEventTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.tylercash.event.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventLifecycleEventTest {

    @Test
    void allRecordsExposeEventId() {
        long id = 42L;
        assertThat(new EventLifecycleEvent.EventCreated(id).eventId()).isEqualTo(id);
        assertThat(new EventLifecycleEvent.EventChannelReady(id).eventId()).isEqualTo(id);
        assertThat(new EventLifecycleEvent.EventRolesReady(id).eventId()).isEqualTo(id);
        assertThat(new EventLifecycleEvent.EventClassified(id).eventId()).isEqualTo(id);
        assertThat(new EventLifecycleEvent.EventPlanned(id).eventId()).isEqualTo(id);
        assertThat(new EventLifecycleEvent.EventPreNotified(id).eventId()).isEqualTo(id);
        assertThat(new EventLifecycleEvent.EventCompleted(id).eventId()).isEqualTo(id);
        assertThat(new EventLifecycleEvent.EventArchived(id).eventId()).isEqualTo(id);
        assertThat(new EventLifecycleEvent.EventCancelRequested(id).eventId()).isEqualTo(id);
        assertThat(new EventLifecycleEvent.EventCancelled(id).eventId()).isEqualTo(id);
        assertThat(new EventLifecycleEvent.EventDeleteRequested(id).eventId()).isEqualTo(id);
        assertThat(new EventLifecycleEvent.EventDeleted(id).eventId()).isEqualTo(id);
        assertThat(new EventLifecycleEvent.EventPreNotifyDue(id).eventId()).isEqualTo(id);
        assertThat(new EventLifecycleEvent.EventCompletionDue(id).eventId()).isEqualTo(id);
        assertThat(new EventLifecycleEvent.EventArchivalDue(id).eventId()).isEqualTo(id);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./gradlew test --tests EventLifecycleEventTest`
Expected: compile failure on `EventLifecycleEvent`.

- [ ] **Step 3: Create the sealed hierarchy**

```java
package dev.tylercash.event.lifecycle;

public sealed interface EventLifecycleEvent {
    long eventId();

    record EventCreated(long eventId)         implements EventLifecycleEvent {}
    record EventChannelReady(long eventId)    implements EventLifecycleEvent {}
    record EventRolesReady(long eventId)      implements EventLifecycleEvent {}
    record EventClassified(long eventId)      implements EventLifecycleEvent {}
    record EventPlanned(long eventId)         implements EventLifecycleEvent {}
    record EventPreNotified(long eventId)     implements EventLifecycleEvent {}
    record EventCompleted(long eventId)       implements EventLifecycleEvent {}
    record EventArchived(long eventId)        implements EventLifecycleEvent {}

    record EventCancelRequested(long eventId) implements EventLifecycleEvent {}
    record EventCancelled(long eventId)       implements EventLifecycleEvent {}
    record EventDeleteRequested(long eventId) implements EventLifecycleEvent {}
    record EventDeleted(long eventId)         implements EventLifecycleEvent {}

    record EventPreNotifyDue(long eventId)    implements EventLifecycleEvent {}
    record EventCompletionDue(long eventId)   implements EventLifecycleEvent {}
    record EventArchivalDue(long eventId)     implements EventLifecycleEvent {}
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests EventLifecycleEventTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/lifecycle/EventLifecycleEvent.java \
        backend/src/test/java/dev/tylercash/event/lifecycle/EventLifecycleEventTest.java
git commit -m "feat(lifecycle): add EventLifecycleEvent sealed hierarchy"
```

### Task 2: Liquibase migration for `listener_invocation` and `event_tick_log`

**Files:**
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append at end, before the `include:` line)

- [ ] **Step 1: Add the changeset**

Open the file, find the trailing `- include:` line, and insert above it:

```yaml
  - changeSet:
      id: add listener_invocation table
      author: tyler
      changes:
        - createTable:
            tableName: listener_invocation
            columns:
              - column:
                  name: event_id
                  type: uuid
                  constraints:
                    nullable: false
              - column:
                  name: lifecycle_event_type
                  type: varchar(64)
                  constraints:
                    nullable: false
              - column:
                  name: listener_name
                  type: varchar(128)
                  constraints:
                    nullable: false
              - column:
                  name: status
                  type: varchar(16)
                  constraints:
                    nullable: false
              - column:
                  name: attempts
                  type: int
                  defaultValueNumeric: 0
                  constraints:
                    nullable: false
              - column:
                  name: last_attempt_at
                  type: timestamptz
              - column:
                  name: next_retry_at
                  type: timestamptz
              - column:
                  name: last_error
                  type: text
              - column:
                  name: created_at
                  type: timestamptz
                  defaultValueComputed: now()
                  constraints:
                    nullable: false
              - column:
                  name: updated_at
                  type: timestamptz
                  defaultValueComputed: now()
                  constraints:
                    nullable: false
        - addPrimaryKey:
            tableName: listener_invocation
            columnNames: event_id, lifecycle_event_type, listener_name
            constraintName: pk_listener_invocation
        - addForeignKeyConstraint:
            baseTableName: listener_invocation
            baseColumnNames: event_id
            referencedTableName: event
            referencedColumnNames: id
            constraintName: fk_listener_invocation_event
            onDelete: CASCADE
        - createIndex:
            tableName: listener_invocation
            indexName: idx_listener_invocation_due
            columns:
              - column:
                  name: status
              - column:
                  name: next_retry_at
            where: "status IN ('PENDING','FAILED')"

  - changeSet:
      id: add event_tick_log table
      author: tyler
      changes:
        - createTable:
            tableName: event_tick_log
            columns:
              - column:
                  name: event_id
                  type: uuid
                  constraints:
                    nullable: false
              - column:
                  name: tick_type
                  type: varchar(32)
                  constraints:
                    nullable: false
              - column:
                  name: fired_at
                  type: timestamptz
                  defaultValueComputed: now()
                  constraints:
                    nullable: false
        - addPrimaryKey:
            tableName: event_tick_log
            columnNames: event_id, tick_type
            constraintName: pk_event_tick_log
        - addForeignKeyConstraint:
            baseTableName: event_tick_log
            baseColumnNames: event_id
            referencedTableName: event
            referencedColumnNames: id
            constraintName: fk_event_tick_log_event
            onDelete: CASCADE
```

Note: `event.id` is `UUID` per the existing schema (verified from `db.changelog-master.yaml`). `EventLifecycleEvent.eventId()` returns `long` per current Event entity getter — verify before committing whether the entity exposes `Long` or `UUID`. If `UUID`, change `eventId()` return type in Task 1 to `UUID` and re-run that test. (Run `grep -n 'private.*id' backend/src/main/java/dev/tylercash/event/event/model/Event.java` to confirm.)

- [ ] **Step 2: Verify migration applies cleanly**

```bash
cd backend && ./gradlew bootRun \
  "--args=--spring.profiles.active=local,nonprod \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/peepbot \
  --spring.datasource.username=peepbot --spring.datasource.password=peepbot \
  --spring.devtools.restart.enabled=false" &
# wait ~15s for startup, check logs for liquibase success
sleep 20 && curl -sf http://localhost:8080/api/actuator/health && kill %1
```

Expected: health returns 200; logs show `add listener_invocation table` and `add event_tick_log table` applied.

- [ ] **Step 3: Verify tables exist**

```bash
docker exec -it $(docker ps --filter ancestor=postgres -q | head -1) \
  psql -U peepbot -d peepbot -c "\dt listener_invocation" \
  -c "\dt event_tick_log"
```

Expected: both tables listed.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(lifecycle): add listener_invocation and event_tick_log tables"
```

### Task 3: JPA entities + repositories for outbox + tick log

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/ListenerInvocation.java`
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/ListenerInvocationId.java`
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/ListenerInvocationStatus.java`
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/ListenerInvocationRepository.java`
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/EventTickLog.java`
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/EventTickLogId.java`
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/EventTickLogRepository.java`
- Test: `backend/src/test/java/dev/tylercash/event/lifecycle/ListenerInvocationRepositoryTest.java`

- [ ] **Step 1: Write the entities**

`ListenerInvocationStatus.java`:
```java
package dev.tylercash.event.lifecycle;

public enum ListenerInvocationStatus { PENDING, SUCCESS, FAILED }
```

`ListenerInvocationId.java`:
```java
package dev.tylercash.event.lifecycle;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ListenerInvocationId implements Serializable {
    private UUID eventId;
    private String lifecycleEventType;
    private String listenerName;

    public ListenerInvocationId() {}
    public ListenerInvocationId(UUID e, String t, String n) {
        this.eventId = e; this.lifecycleEventType = t; this.listenerName = n;
    }
    @Override public boolean equals(Object o) {
        if (!(o instanceof ListenerInvocationId i)) return false;
        return Objects.equals(eventId, i.eventId)
            && Objects.equals(lifecycleEventType, i.lifecycleEventType)
            && Objects.equals(listenerName, i.listenerName);
    }
    @Override public int hashCode() {
        return Objects.hash(eventId, lifecycleEventType, listenerName);
    }
}
```

`ListenerInvocation.java`:
```java
package dev.tylercash.event.lifecycle;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "listener_invocation")
@IdClass(ListenerInvocationId.class)
@Data
@NoArgsConstructor
public class ListenerInvocation {
    @Id @Column(name = "event_id")             private UUID eventId;
    @Id @Column(name = "lifecycle_event_type") private String lifecycleEventType;
    @Id @Column(name = "listener_name")        private String listenerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ListenerInvocationStatus status;

    @Column(nullable = false)             private int attempts;
    @Column(name = "last_attempt_at")     private Instant lastAttemptAt;
    @Column(name = "next_retry_at")       private Instant nextRetryAt;
    @Column(name = "last_error", columnDefinition = "text") private String lastError;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
```

`ListenerInvocationRepository.java`:
```java
package dev.tylercash.event.lifecycle;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListenerInvocationRepository
        extends JpaRepository<ListenerInvocation, ListenerInvocationId> {

    @Query("""
        SELECT i FROM ListenerInvocation i
        WHERE i.status IN ('PENDING','FAILED')
          AND (i.nextRetryAt IS NULL OR i.nextRetryAt <= :now)
        ORDER BY i.nextRetryAt ASC NULLS FIRST
        """)
    List<ListenerInvocation> findDueForRetry(@Param("now") Instant now, Pageable page);
}
```

Mirror the same shape for `EventTickLog`, `EventTickLogId`, `EventTickLogRepository` (PK is `(eventId UUID, tickType String)`, single column `firedAt Instant`).

- [ ] **Step 2: Write repository test**

```java
package dev.tylercash.event.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.db.repository.EventRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ListenerInvocationRepositoryTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17.5");

    @Autowired ListenerInvocationRepository repo;
    @Autowired EventRepository events;

    @Test
    void findDueForRetry_returnsPendingAndFailedDueRows() {
        Event e = newEvent();
        events.save(e);

        ListenerInvocation pending = newInvocation(e.getId(), "EventCreated", "Test Listener", ListenerInvocationStatus.PENDING, null);
        ListenerInvocation failedDue = newInvocation(e.getId(), "EventCreated", "Other Listener", ListenerInvocationStatus.FAILED, Instant.now().minusSeconds(10));
        ListenerInvocation failedFuture = newInvocation(e.getId(), "EventCreated", "Future Listener", ListenerInvocationStatus.FAILED, Instant.now().plusSeconds(60));
        ListenerInvocation success = newInvocation(e.getId(), "EventCreated", "Done Listener", ListenerInvocationStatus.SUCCESS, null);
        repo.saveAll(java.util.List.of(pending, failedDue, failedFuture, success));

        var due = repo.findDueForRetry(Instant.now(), PageRequest.of(0, 10));
        assertThat(due).extracting(ListenerInvocation::getListenerName)
            .containsExactlyInAnyOrder("Test Listener", "Other Listener");
    }

    private static Event newEvent() { /* construct minimal event with UUID + required fields */ throw new UnsupportedOperationException("fill in"); }
    private static ListenerInvocation newInvocation(UUID eid, String type, String name, ListenerInvocationStatus s, Instant nextRetry) {
        ListenerInvocation i = new ListenerInvocation();
        i.setEventId(eid); i.setLifecycleEventType(type); i.setListenerName(name);
        i.setStatus(s); i.setNextRetryAt(nextRetry);
        return i;
    }
}
```

The implementer must fill in `newEvent()` from looking at `Event` constructor / existing test factories. Look at `EventControllerTest` or `AttendanceServiceTest` for an example.

- [ ] **Step 3: Run the test**

Run: `./gradlew test --tests ListenerInvocationRepositoryTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/lifecycle/ \
        backend/src/test/java/dev/tylercash/event/lifecycle/ListenerInvocationRepositoryTest.java
git commit -m "feat(lifecycle): add outbox + tick log JPA entities"
```

### Task 4: `DurableEventListener` interface

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/DurableEventListener.java`

- [ ] **Step 1: Create the interface**

```java
package dev.tylercash.event.lifecycle;

/**
 * A listener invoked when a lifecycle event matching {@link #eventType()} is published.
 * The publisher creates a {@link ListenerInvocation} outbox row in the publishing
 * transaction; if the row reaches SUCCESS, this listener will not be invoked again
 * for the same (event_id, lifecycle_event_type) tuple.
 *
 * Implementations MUST be idempotent: a listener may be re-invoked after a partial
 * crash. Use "find by external ID, create if missing" or conditional state updates.
 */
public interface DurableEventListener<E extends EventLifecycleEvent> {

    /**
     * Stable, human-readable identifier. Used as the outbox row key AND rendered in
     * logs / dashboards / admin UI. Title Case with spaces — e.g. "Immich Album Prep".
     * Renaming is a data migration. Must be unique across all DurableEventListener beans.
     */
    String name();

    Class<E> eventType();

    /** Throw to signal failure; the dispatcher will record FAILED + schedule retry. */
    void handle(E event) throws Exception;
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/lifecycle/DurableEventListener.java
git commit -m "feat(lifecycle): add DurableEventListener interface"
```

### Task 5: `EventLifecyclePublisher`

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/EventLifecyclePublisher.java`
- Test: `backend/src/test/java/dev/tylercash/event/lifecycle/EventLifecyclePublisherTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.tylercash.event.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class EventLifecyclePublisherTest {

    @Test
    void publish_insertsPendingRowPerMatchingListener() {
        ListenerInvocationRepository repo = mock(ListenerInvocationRepository.class);
        ApplicationEventPublisher spring = mock(ApplicationEventPublisher.class);
        DurableEventListener<EventLifecycleEvent.EventCreated> matching =
            stubListener("L1", EventLifecycleEvent.EventCreated.class);
        DurableEventListener<EventLifecycleEvent.EventArchived> nonMatching =
            stubListener("L2", EventLifecycleEvent.EventArchived.class);

        var publisher = new EventLifecyclePublisher(
            spring, repo, List.of(matching, nonMatching), new SimpleMeterRegistry());

        UUID id = UUID.randomUUID();
        publisher.publish(new EventLifecycleEvent.EventCreated(id));

        verify(repo).save(argThat(inv ->
            inv.getEventId().equals(id)
            && inv.getLifecycleEventType().equals("EventCreated")
            && inv.getListenerName().equals("L1")
            && inv.getStatus() == ListenerInvocationStatus.PENDING));
        verifyNoMoreInteractions(repo);
        verify(spring).publishEvent(any(EventLifecycleEvent.EventCreated.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <E extends EventLifecycleEvent> DurableEventListener<E> stubListener(
            String name, Class<E> eventType) {
        DurableEventListener mock = mock(DurableEventListener.class);
        when(mock.name()).thenReturn(name);
        when(mock.eventType()).thenReturn(eventType);
        return mock;
    }
}
```

> **Note:** the eventId type is `UUID` per the actual `Event.id` column. If Task 1 was implemented with `long`, fix it now: change `EventLifecycleEvent.eventId()` to return `UUID` and update all 15 records. Re-run `EventLifecycleEventTest`.

- [ ] **Step 2: Run the test (it will fail to compile)**

Run: `./gradlew test --tests EventLifecyclePublisherTest`
Expected: compile failure on `EventLifecyclePublisher`.

- [ ] **Step 3: Implement the publisher**

```java
package dev.tylercash.event.lifecycle;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class EventLifecyclePublisher {
    private final ApplicationEventPublisher springPublisher;
    private final ListenerInvocationRepository invocations;
    private final List<DurableEventListener<?>> listeners;
    private final MeterRegistry meterRegistry;

    public EventLifecyclePublisher(
            ApplicationEventPublisher springPublisher,
            ListenerInvocationRepository invocations,
            List<DurableEventListener<?>> listeners,
            MeterRegistry meterRegistry) {
        this.springPublisher = springPublisher;
        this.invocations = invocations;
        this.listeners = listeners;
        this.meterRegistry = meterRegistry;
    }

    public void publish(EventLifecycleEvent event) {
        String type = event.getClass().getSimpleName();
        log.debug("Publishing lifecycle event {} for event {}", type, event.eventId());
        meterRegistry.counter("event.lifecycle.published", "type", type).increment();

        for (DurableEventListener<?> listener : listeners) {
            if (!listener.eventType().isInstance(event)) continue;
            ListenerInvocation row = new ListenerInvocation();
            row.setEventId(event.eventId());
            row.setLifecycleEventType(type);
            row.setListenerName(listener.name());
            row.setStatus(ListenerInvocationStatus.PENDING);
            invocations.save(row);
        }
        // Spring's ApplicationEventPublisher fans out to @TransactionalEventListener
        // beans, including the post-commit dispatcher (see EventBusConfig).
        springPublisher.publishEvent(event);
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests EventLifecyclePublisherTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/lifecycle/EventLifecyclePublisher.java \
        backend/src/test/java/dev/tylercash/event/lifecycle/EventLifecyclePublisherTest.java
git commit -m "feat(lifecycle): add EventLifecyclePublisher with outbox insert"
```

### Task 6: `EventBusConfig` — executor, multicaster, post-commit dispatcher, name uniqueness

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/EventBusConfig.java`
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/PostCommitDispatcher.java`
- Test: `backend/src/test/java/dev/tylercash/event/lifecycle/PostCommitDispatcherTest.java`

The dispatcher is the bean that receives lifecycle events via `@TransactionalEventListener(AFTER_COMMIT)`, looks up matching listeners, runs each on the executor, and updates the outbox row.

- [ ] **Step 1: Implement `PostCommitDispatcher`**

```java
package dev.tylercash.event.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
public class PostCommitDispatcher {

    static final long INVOCATION_TIMEOUT_SECONDS = 60;

    private final List<DurableEventListener<?>> listeners;
    private final ListenerInvocationRepository invocations;
    private final AsyncTaskExecutor executor;
    private final BackoffPolicy backoff;

    public PostCommitDispatcher(
            List<DurableEventListener<?>> listeners,
            ListenerInvocationRepository invocations,
            AsyncTaskExecutor eventBusExecutor,
            BackoffPolicy backoff) {
        this.listeners = listeners;
        this.invocations = invocations;
        this.executor = eventBusExecutor;
        this.backoff = backoff;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommit(EventLifecycleEvent event) {
        String type = event.getClass().getSimpleName();
        for (DurableEventListener<?> listener : listeners) {
            if (!listener.eventType().isInstance(event)) continue;
            executor.submit(() -> invokeWithTimeout(listener, event, type));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invokeWithTimeout(DurableEventListener listener,
                                   EventLifecycleEvent event, String type) {
        var id = new ListenerInvocationId(event.eventId(), type, listener.name());
        ListenerInvocation row = invocations.findById(id).orElse(null);
        if (row == null || row.getStatus() == ListenerInvocationStatus.SUCCESS) return;

        Future<?> future = executor.submit(() -> {
            try { listener.handle(event); } catch (Exception e) { throw new RuntimeException(e); }
        });
        try {
            future.get(INVOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            markSuccess(row);
        } catch (TimeoutException te) {
            future.cancel(true);
            markFailed(row, "Timeout after " + INVOCATION_TIMEOUT_SECONDS + "s");
        } catch (ExecutionException ee) {
            markFailed(row, ee.getCause() == null ? ee.toString() : ee.getCause().toString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            markFailed(row, "Interrupted");
        }
    }

    private void markSuccess(ListenerInvocation row) {
        row.setStatus(ListenerInvocationStatus.SUCCESS);
        row.setLastAttemptAt(Instant.now());
        row.setLastError(null);
        invocations.save(row);
    }

    private void markFailed(ListenerInvocation row, String error) {
        row.setStatus(ListenerInvocationStatus.FAILED);
        row.setAttempts(row.getAttempts() + 1);
        row.setLastAttemptAt(Instant.now());
        row.setNextRetryAt(backoff.nextRetryAt(row.getAttempts()));
        row.setLastError(error);
        invocations.save(row);
        log.warn("Listener '{}' failed for event {}: {}",
                row.getListenerName(), row.getEventId(), error);
    }
}
```

- [ ] **Step 2: Implement `BackoffPolicy`**

```java
package dev.tylercash.event.lifecycle;

import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class BackoffPolicy {
    private static final Duration BASE = Duration.ofMinutes(1);
    private static final Duration CAP = Duration.ofHours(4);

    public Instant nextRetryAt(int attempts) {
        long minutes = (long) BASE.toMinutes() << Math.min(attempts - 1, 30);
        Duration delay = Duration.ofMinutes(minutes);
        if (delay.compareTo(CAP) > 0) delay = CAP;
        return Instant.now().plus(delay);
    }
}
```

Add a unit test asserting: attempts=1 → ~1m, attempts=2 → ~2m, attempts=8 → 128m, attempts=10 → CAP (240m).

- [ ] **Step 3: Implement `EventBusConfig`**

```java
package dev.tylercash.event.lifecycle;

import jakarta.annotation.PostConstruct;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
@RequiredArgsConstructor
public class EventBusConfig {

    private final List<DurableEventListener<?>> listeners;

    @Bean
    public AsyncTaskExecutor eventBusExecutor() {
        var ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("event-bus-");
        ex.initialize();
        return ex;
    }

    @PostConstruct
    void validateListenerNamesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (var l : listeners) {
            if (!seen.add(l.name())) {
                throw new IllegalStateException(
                    "Duplicate DurableEventListener name: " + l.name());
            }
        }
    }
}
```

- [ ] **Step 4: Write `PostCommitDispatcherTest`**

Use Mockito to stub a listener that throws on first call and succeeds on second; assert: row marked FAILED with `nextRetryAt` set and `attempts=1`. Use a synchronous `SyncTaskExecutor` instead of the real pool to keep the test deterministic.

- [ ] **Step 5: Run all lifecycle tests**

Run: `./gradlew test --tests 'dev.tylercash.event.lifecycle.*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/lifecycle/PostCommitDispatcher.java \
        backend/src/main/java/dev/tylercash/event/lifecycle/BackoffPolicy.java \
        backend/src/main/java/dev/tylercash/event/lifecycle/EventBusConfig.java \
        backend/src/test/java/dev/tylercash/event/lifecycle/
git commit -m "feat(lifecycle): add post-commit dispatcher, backoff, bus config"
```

### Task 7: `DurableListenerRetryPoller`

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/DurableListenerRetryPoller.java`

The poller scans for `findDueForRetry`, re-invokes via `PostCommitDispatcher`'s same single-row method (refactor that method into a package-private `invokeOnce(ListenerInvocation)` so both the post-commit dispatcher and the poller share it).

- [ ] **Step 1: Refactor dispatcher to expose `invokeOnce`** — extract the body of `invokeWithTimeout` into a method `invokeOnce(ListenerInvocation row)` that loads the listener by name from a lookup map built in `@PostConstruct`.

- [ ] **Step 2: Implement the poller**

```java
package dev.tylercash.event.lifecycle;

import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DurableListenerRetryPoller {

    private static final int BATCH = 100;

    private final ListenerInvocationRepository invocations;
    private final PostCommitDispatcher dispatcher;
    private final Clock clock;

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "DurableListenerRetryPoller", lockAtMostFor = "PT5M")
    public void retry() {
        List<ListenerInvocation> due = invocations.findDueForRetry(
            clock.instant(), PageRequest.of(0, BATCH));
        if (due.isEmpty()) return;
        log.info("Retrying {} listener invocations", due.size());
        for (ListenerInvocation row : due) {
            dispatcher.invokeOnce(row);
        }
    }
}
```

- [ ] **Step 3: Add a stuck-counter scheduled job** in the poller (or a sibling class): every hour, count `WHERE status='FAILED' AND attempts>24` and emit `event.lifecycle.listener.stuck` gauge.

- [ ] **Step 4: Test**

Integration test (Testcontainers): seed 3 rows (1 PENDING, 1 FAILED-due, 1 FAILED-future), run `retry()`, assert dispatcher invoked twice with the right rows.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/lifecycle/DurableListenerRetryPoller.java \
        backend/src/test/java/dev/tylercash/event/lifecycle/DurableListenerRetryPollerTest.java
git commit -m "feat(lifecycle): add retry poller with stuck-counter alerting"
```

### Task 8: `EventTickScheduler`

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/EventTickScheduler.java`
- Test: `backend/src/test/java/dev/tylercash/event/lifecycle/EventTickSchedulerTest.java`

The scheduler reads the existing time-based guards (look at `PreEventNotifyOperation.guard()`, `CompleteOperation.guard()`, `ArchiveOperation.guard()`, `DeleteOperation.guard()`) to derive each window's bounds. Implementer must read those four files to translate guards into the `EventRepository` query that returns events in the window AND not already in `event_tick_log` for that tick type.

- [ ] **Step 1: Add `EventRepository` finder for windowed events**

Add to `EventRepository`:
```java
@Query("SELECT e FROM Event e WHERE e.dateTime BETWEEN :from AND :to AND e.state = :state")
List<Event> findInDateWindow(@Param("from") ZonedDateTime from,
                             @Param("to") ZonedDateTime to,
                             @Param("state") EventState state);
```

(Adjust to match the actual date field name on Event — check `Event.java`.)

- [ ] **Step 2: Implement the scheduler**

```java
package dev.tylercash.event.lifecycle;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import java.time.Clock;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventTickScheduler {

    private final EventRepository events;
    private final EventTickLogRepository tickLog;
    private final EventLifecyclePublisher publisher;
    private final Clock clock;

    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "EventTickScheduler", lockAtMostFor = "PT5M")
    public void emit() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        emitTick("PRE_NOTIFY",  now,                     now.plusHours(2),   EventState.PLANNED,
                 id -> publisher.publish(new EventLifecycleEvent.EventPreNotifyDue(id)));
        emitTick("COMPLETION",  now.minusYears(1),       now,                EventState.PRE_NOTIFIED,
                 id -> publisher.publish(new EventLifecycleEvent.EventCompletionDue(id)));
        emitTick("ARCHIVAL",    now.minusYears(2),       now.minusDays(30),  EventState.POST_COMPLETED,
                 id -> publisher.publish(new EventLifecycleEvent.EventArchivalDue(id)));
    }

    private void emitTick(String tickType, ZonedDateTime from, ZonedDateTime to,
                          EventState state, java.util.function.Consumer<java.util.UUID> publish) {
        for (Event e : events.findInDateWindow(from, to, state)) {
            EventTickLogId id = new EventTickLogId(e.getId(), tickType);
            if (tickLog.existsById(id)) continue;
            EventTickLog row = new EventTickLog();
            row.setEventId(e.getId()); row.setTickType(tickType);
            tickLog.save(row);
            publish.accept(e.getId());
        }
    }
}
```

> Implementer: verify the `from`/`to` bounds against the existing guards. The guards are the source of truth — if `PreEventNotifyOperation.guard()` checks `now.isAfter(event.getDateTime().minusHours(2)) && now.isBefore(event.getDateTime())`, the window is `(now, now+2h)`. Pre-deploy check: query `event` for any rows in transient states.

- [ ] **Step 3: Test with `Clock.fixed(...)`**

Verify: events whose date falls in the window get a tick and a publish; events already in `event_tick_log` for that tick type are skipped; events outside the window are skipped.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/lifecycle/EventTickScheduler.java \
        backend/src/main/java/dev/tylercash/event/db/repository/EventRepository.java \
        backend/src/test/java/dev/tylercash/event/lifecycle/EventTickSchedulerTest.java
git commit -m "feat(lifecycle): add EventTickScheduler with at-most-once tick log"
```

### Task 9: Admin endpoints (retry-now / delete)

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/admin/ListenerInvocationAdminController.java`

- [ ] **Step 1: Implement controller**

```java
package dev.tylercash.event.lifecycle.admin;

import dev.tylercash.event.lifecycle.*;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/listener-invocations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ListenerInvocationAdminController {

    private final ListenerInvocationRepository repo;

    @PostMapping("/{eventId}/{type}/{listenerName}/retry-now")
    public ResponseEntity<Void> retryNow(@PathVariable UUID eventId,
                                         @PathVariable String type,
                                         @PathVariable String listenerName) {
        var id = new ListenerInvocationId(eventId, type, listenerName);
        return repo.findById(id).map(row -> {
            row.setNextRetryAt(Instant.now());
            row.setStatus(ListenerInvocationStatus.PENDING);
            repo.save(row);
            return ResponseEntity.noContent().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{eventId}/{type}/{listenerName}")
    public ResponseEntity<Void> abandon(@PathVariable UUID eventId,
                                        @PathVariable String type,
                                        @PathVariable String listenerName) {
        var id = new ListenerInvocationId(eventId, type, listenerName);
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 2: Add `@WebMvcTest` covering both endpoints with `@WithMockUser(roles="ADMIN")` and a non-admin negative case (403)**.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/lifecycle/admin/ \
        backend/src/test/java/dev/tylercash/event/lifecycle/admin/
git commit -m "feat(lifecycle): add admin retry-now and abandon endpoints"
```

---

## Phase 2: Listener migrations

Each task in this phase has the same shape:

1. Read `backend/src/main/java/dev/tylercash/event/event/statemachine/operation/<Op>Operation.java`.
2. Create `backend/src/main/java/dev/tylercash/event/lifecycle/listener/<Listener>.java` implementing `DurableEventListener<E>`.
3. Copy the body of `*Operation.action()` into `handle(E event)`. Replace
   `context.getExtendedState().get("event", Event.class)` with
   `eventRepository.findById(event.eventId()).orElseThrow()`.
4. If the operation set state via the state machine transition target, set it explicitly
   at the end of `handle`: `event.setState(EventState.X); eventRepository.save(event);`.
5. Replace any guard logic (only `PreEventNotify`, `Complete`, `Archive`, `Delete` have
   guards) with the assumption that the trigger event was only emitted when the guard
   condition was met (the guard logic now lives in `EventTickScheduler`).
6. At the end of `handle`, publish the next event in the chain via the injected
   `EventLifecyclePublisher` (per the migration table).
7. Write a unit test: stub repos, invoke `handle`, assert side-effects + state set + next
   event published. Add an idempotency test: call `handle` twice, assert no duplicate
   side-effects.

### Migration table reference

| Listener class           | `name()`                  | Subscribes to              | Emits on success         | Sets `Event.state` to | Source op             |
|--------------------------|---------------------------|----------------------------|--------------------------|-----------------------|-----------------------|
| `DiscordChannelInitListener`   | "Discord Channel Init"   | `EventCreated`           | `EventChannelReady`    | `INIT_CHANNEL`     | `InitChannelOperation` |
| `DiscordRolesInitListener`     | "Discord Roles Init"     | `EventChannelReady`      | `EventRolesReady`      | `INIT_ROLES`       | `InitRolesOperation` |
| `EventClassifyListener`        | "Event Classify"         | `EventRolesReady`        | `EventClassified`      | `CLASSIFY`         | `ClassifyOperation` |
| `EventInitCompleteListener`    | "Event Init Complete"    | `EventClassified`        | `EventPlanned`         | `PLANNED`          | `InitCompleteOperation` |
| `PreEventNotificationListener` | "Pre-Event Notification" | `EventPreNotifyDue`      | `EventPreNotified`     | `PRE_NOTIFIED`     | `PreEventNotifyOperation` |
| `ImmichAlbumPrepListener`      | "Immich Album Prep"      | `EventPreNotified`       | (none)                 | (no state change)  | `PrepareAlbumOperation` |
| `EventCompleteListener`        | "Event Complete"         | `EventCompletionDue`     | `EventCompleted`       | `POST_COMPLETED`   | `CompleteOperation` |
| `ImmichAlbumPostListener`      | "Immich Album Post"      | `EventCompleted`         | (none)                 | (no state change)  | `PostAlbumOperation` |
| `EventArchiveListener`         | "Event Archive"          | `EventArchivalDue`       | `EventArchived`        | `ARCHIVED`         | `ArchiveOperation` |
| `EventCancelListener`          | "Event Cancel"           | `EventCancelRequested`   | `EventCancelled`       | `CANCELLED`        | `CancelOperation` |
| `EventDeleteListener`          | "Event Delete"           | `EventDeleteRequested`   | `EventDeleted`         | `DELETED`          | `DeleteOperation` |

### Task 10: `DiscordChannelInitListener` (worked example)

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/listener/DiscordChannelInitListener.java`
- Test: `backend/src/test/java/dev/tylercash/event/lifecycle/listener/DiscordChannelInitListenerTest.java`

- [ ] **Step 1: Read the source operation**

```bash
cat backend/src/main/java/dev/tylercash/event/event/statemachine/operation/InitChannelOperation.java
```

- [ ] **Step 2: Implement listener**

```java
package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordChannelService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.lifecycle.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DiscordChannelInitListener
        implements DurableEventListener<EventLifecycleEvent.EventCreated> {

    private final EventRepository eventRepository;
    private final DiscordChannelService discordChannelService;
    private final EventLifecyclePublisher publisher;

    @Override public String name() { return "Discord Channel Init"; }
    @Override public Class<EventLifecycleEvent.EventCreated> eventType() {
        return EventLifecycleEvent.EventCreated.class;
    }

    @Override
    @Transactional
    public void handle(EventLifecycleEvent.EventCreated e) {
        Event event = eventRepository.findById(e.eventId()).orElseThrow();
        // Idempotency: if channel already created, no-op.
        if (event.getChannelId() != 0) {
            log.info("Channel already exists for event {}, skipping", event.getId());
        } else {
            // === BEGIN ported from InitChannelOperation.action() ===
            // (paste the body verbatim, replacing any state-machine context lookups
            //  with the local 'event' variable)
            // === END ported ===
        }
        event.setState(EventState.INIT_CHANNEL);
        eventRepository.save(event);
        publisher.publish(new EventLifecycleEvent.EventChannelReady(event.getId()));
    }
}
```

- [ ] **Step 3: Write idempotency test** — invoke twice, assert `discordChannelService` called exactly once on the second call (gated by `channelId != 0`).

- [ ] **Step 4: Run test**

Run: `./gradlew test --tests DiscordChannelInitListenerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/lifecycle/listener/DiscordChannelInitListener.java \
        backend/src/test/java/dev/tylercash/event/lifecycle/listener/DiscordChannelInitListenerTest.java
git commit -m "feat(lifecycle): port InitChannelOperation to listener"
```

### Tasks 11–20: Remaining 10 listeners

Apply the Task 10 pattern to each of the remaining operations in the migration table. One commit per listener. For each:

- The "idempotency guard" is operation-specific. Read the existing operation; the obvious guard is "if the side-effect already happened, no-op." For Discord message posts, check whether a message ID is stored on the event. For role assignments, check the role state. For Immich, "find album by external ID, create if missing" already exists in `ImmichService` — confirm and call the find-or-create path.
- For `PreEventNotificationListener` / `EventCompleteListener` / `EventArchiveListener` / `EventDeleteListener`: the guard logic was time-based, lives in `EventTickScheduler` now. Listener bodies have no time-based guard, only the side-effect-already-happened idempotency check.
- For `EventCancelListener`: the trigger is user-initiated. Wire the Discord cancel slash-command handler (find via `grep -rln "EventStateMachineEvent.CANCEL" backend/src/main`) to publish `EventCancelRequested` instead of firing the state-machine signal.
- For `EventDeleteListener`: the original `DeleteOperation.guard()` checks "in CANCELLED/ARCHIVED for >N days." That window check moves into `EventTickScheduler`'s `emitTick(...)` for `DELETION`. Add a fourth tick type and emit `EventDeleteRequested`. Update Task 8 if not already covered.

Do NOT delete the source operation in this phase. Both run side-by-side after Phase 2 — but with no callers of the state machine left after Phase 3, the operations become dead code, removed in Phase 3.

---

## Phase 3: Wire creation + cutover

### Task 21: Wire `EventService.create(...)` to publish `EventCreated`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/event/EventService.java`

- [ ] **Step 1: Inject `EventLifecyclePublisher`** into `EventService`. Find the create method (the one that returns the persisted `Event`) and append:

```java
publisher.publish(new EventLifecycleEvent.EventCreated(saved.getId()));
```

The publish call must be inside the same `@Transactional` boundary as the `eventRepository.save(...)` so the outbox row inserts atomically.

- [ ] **Step 2: Add an integration test** that creates an event via `EventService.create` and asserts a `listener_invocation` row exists for each registered listener whose `eventType` is `EventCreated`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/event/EventService.java \
        backend/src/test/java/dev/tylercash/event/event/EventServiceCreatePublishTest.java
git commit -m "feat(lifecycle): publish EventCreated on event creation"
```

### Task 22: End-to-end saga integration test

**Files:**
- Create: `backend/src/test/java/dev/tylercash/event/lifecycle/EventLifecycleSagaIntegrationTest.java`

- [ ] **Step 1: Write a `@SpringBootTest` (Testcontainers Postgres) that:**
  - Mocks Discord and Immich services to record calls but no-op.
  - Creates an event via `EventService.create`.
  - Polls `listener_invocation` until rows for `EventCreated/Discord Channel Init`, `EventChannelReady/Discord Roles Init`, `EventRolesReady/Event Classify`, `EventClassified/Event Init Complete` all reach `SUCCESS` (timeout 30s).
  - Asserts `event.state == PLANNED`.
  - Advances `Clock` past `event.dateTime - 2h`, triggers `EventTickScheduler.emit()`, polls until `Pre-Event Notification` reaches SUCCESS.
  - Continues advancing through `Event Complete`, `Event Archive`.
  - Asserts the final state is `ARCHIVED`.

- [ ] **Step 2: Run**

Run: `./gradlew test --tests EventLifecycleSagaIntegrationTest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/dev/tylercash/event/lifecycle/EventLifecycleSagaIntegrationTest.java
git commit -m "test(lifecycle): end-to-end saga integration test"
```

### Task 23: Pre-deploy operator readiness

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/global/EventCreationToggle.java`
- Modify: `backend/src/main/java/dev/tylercash/event/event/EventController.java`

- [ ] **Step 1: Add a runtime toggle** backed by `dev.tylercash.event-creation.enabled` config (default `true`). When false, `POST /api/event` returns 503 with body `{"error":"Event creation temporarily disabled for maintenance"}`. The existing Discord create-event slash command handler returns the same message in-channel.

- [ ] **Step 2: Add an admin endpoint** `POST /admin/event-creation/{enable|disable}` that flips the toggle without restart.

- [ ] **Step 3: Test** — `@WebMvcTest` proves both states.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/global/EventCreationToggle.java \
        backend/src/main/java/dev/tylercash/event/event/EventController.java \
        backend/src/test/java/dev/tylercash/event/global/EventCreationToggleTest.java
git commit -m "feat(event): add operator toggle to pause event creation"
```

### Task 24: Delete state machine

**Files:**
- Delete: every file under `backend/src/main/java/dev/tylercash/event/event/statemachine/`
- Delete: every test under `backend/src/test/java/dev/tylercash/event/event/statemachine/`
- Modify: `backend/build.gradle` — remove `spring-statemachine-starter`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` — append a changeset that drops Spring State Machine's tables (run `\dt` in psql to find them; typical names: `state_machine`, `state_machine_context`)
- Modify: any caller that referenced `EventStateMachineService` or `EventStateMachineEvent`

- [ ] **Step 1: Find call sites**

```bash
grep -rln "EventStateMachineService\|EventStateMachineEvent\|EventStateMachineConfig\|@EnableStateMachineFactory" backend/src
```

Expected: only the state-machine files themselves and tests. If any production caller remains (e.g. a Discord listener wiring a CANCEL signal), that caller was supposed to be migrated in Tasks 10–20 — fix now.

- [ ] **Step 2: Delete files**

```bash
git rm -r backend/src/main/java/dev/tylercash/event/event/statemachine/ \
         backend/src/test/java/dev/tylercash/event/event/statemachine/
```

- [ ] **Step 3: Remove dependency**

Edit `backend/build.gradle`, remove the `spring-statemachine-starter` dependency line. Run `./gradlew dependencies | grep -i statemachine` to confirm gone.

- [ ] **Step 4: Append drop-table changeset**

In `db.changelog-master.yaml` (before the `include:` line):

```yaml
  - changeSet:
      id: drop spring state machine tables
      author: tyler
      changes:
        - dropTable: { tableName: state_machine_context }
        - dropTable: { tableName: state_machine }
```

(Verify table names against actual schema first: `\dt` in psql.)

- [ ] **Step 5: Compile + test**

```bash
cd backend && ./gradlew spotlessApply spotlessCheck test e2eTest
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(lifecycle): remove Spring State Machine"
```

### Task 25: Drop unused EventState constants

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/event/model/EventState.java` — remove `POST_ALBUM_READY`, `POST_ALBUM_SHARED`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` — sanity changeset (no rows in those states per pre-deploy check, so no data migration needed; if any exist, `UPDATE event SET state = 'POST_COMPLETED' WHERE state IN (...)`)

- [ ] **Step 1: Pre-check**

```bash
docker exec -it $(docker ps --filter ancestor=postgres -q | head -1) \
  psql -U peepbot -d peepbot \
  -c "SELECT state, COUNT(*) FROM event GROUP BY state;"
```

Expected: no rows in `POST_ALBUM_READY` or `POST_ALBUM_SHARED`. If any exist, advance them manually first.

- [ ] **Step 2: Remove enum constants**

Delete the two values from `EventState.java`. Compile to find any remaining references (likely none, as the album work is now in the immich listeners).

- [ ] **Step 3: Run all tests**

```bash
cd backend && ./gradlew test e2eTest
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(event): drop POST_ALBUM_READY and POST_ALBUM_SHARED states"
```

### Task 26: Final verification

- [ ] **Step 1: Spotless**

```bash
cd backend && ./gradlew spotlessCheck
```

Expected: PASS.

- [ ] **Step 2: All tests**

```bash
cd backend && ./gradlew test e2eTest
```

Expected: PASS.

- [ ] **Step 3: Smoke run**

Boot the backend with the local profile, create an event via the frontend, verify in psql that `listener_invocation` rows exist and reach SUCCESS:

```sql
SELECT listener_name, status, attempts FROM listener_invocation
 WHERE event_id = '<id>' ORDER BY created_at;
```

- [ ] **Step 4: Self-review the diff**

```bash
git log --oneline main..HEAD
git diff main..HEAD --stat
```

Look for: missed `EventStateMachineService` references, leftover `*Operation` classes, `TODO`/`FIXME` markers added during the migration.

- [ ] **Step 5: Open PR**

```bash
gh pr create --title "refactor(lifecycle): replace state machine with durable event bus" \
  --body "$(cat <<'EOF'
## Summary
- Adds `EventLifecyclePublisher` + `DurableEventListener` framework backed by a `listener_invocation` outbox
- Migrates all 11 state-machine operations onto the bus as durable listeners
- Adds `EventTickScheduler` for time-based events (pre-notify, completion, archival, deletion)
- Adds operator toggle to pause event creation during cutover
- Deletes Spring State Machine and `*Operation` classes
- Drops `POST_ALBUM_READY` / `POST_ALBUM_SHARED` enum constants

See spec: `docs/superpowers/specs/2026-05-04-event-lifecycle-bus-design.md`

## Cutover plan
1. Disable event creation via `POST /admin/event-creation/disable`
2. Confirm `SELECT state, COUNT(*) FROM event GROUP BY state` shows only terminal/PLANNED states
3. Deploy
4. Smoke test with one event
5. Re-enable creation

## Test plan
- [ ] `./gradlew test` passes
- [ ] `./gradlew e2eTest` passes
- [ ] Manual: create event in nonprod, observe full saga in `listener_invocation`
- [ ] Manual: simulate listener failure (toss exception in one listener), confirm retry succeeds

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-review checklist

Run before declaring complete:

- [ ] Spec coverage: every section in the spec maps to a task above. (Trade-offs and alternatives sections are documentation, not code — no task needed.)
- [ ] Type consistency: `EventLifecycleEvent.eventId()` returns `UUID` (matches `Event.id`); same in records, repository, dispatcher, controller, schema.
- [ ] Listener `name()` strings exactly match the migration table (case + spacing).
- [ ] Each listener has an idempotency check appropriate to its side-effect.
- [ ] Liquibase changesets append to `db.changelog-master.yaml` (do not edit historical changesets).
- [ ] No `TODO`, `TBD`, or `XXX` markers in committed code.
- [ ] Spotless and all tests pass before each commit.
