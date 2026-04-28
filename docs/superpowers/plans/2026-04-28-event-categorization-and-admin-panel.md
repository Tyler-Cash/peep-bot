# Event Categorization Retry & Admin Panel Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add exponential-backoff retry for PLANNED events with unknown category, expose a manual re-categorize button in the admin panel, remove the edit button from the admin panel, and hide all write operations when an event is in the past.

**Architecture:** The retry mechanism is a new scheduled service (`CategorizationRetryService`) backed by a new `event_classification_attempt` table that tracks retry state per event. The manual trigger is a new `POST /event/{id}/recategorize` endpoint. The admin panel UI changes are confined to `EventDetail.tsx`.

**Tech Stack:** Spring Boot 3.5.8, Java 21, Liquibase (YAML), JPA/Hibernate, ShedLock, React 19, TypeScript, SWR

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `backend/src/main/resources/db/changelog/db.changelog-master.yaml` | Modify | Add migration for `event_classification_attempt` table |
| `backend/src/main/java/dev/tylercash/event/event/EventClassificationAttempt.java` | Create | JPA entity for retry tracking |
| `backend/src/main/java/dev/tylercash/event/db/repository/EventClassificationAttemptRepository.java` | Create | Spring Data repo for the entity |
| `backend/src/main/java/dev/tylercash/event/db/repository/EventRepository.java` | Modify | Add `findPlannedEventsWithoutCategory` query |
| `backend/src/main/java/dev/tylercash/event/event/CategorizationRetryService.java` | Create | Scheduled retry with exponential backoff |
| `backend/src/test/java/dev/tylercash/event/event/CategorizationRetryServiceTest.java` | Create | Unit tests for retry service |
| `backend/src/main/java/dev/tylercash/event/event/EventService.java` | Modify | Inject `EmbeddingService`; add `recategorizeEvent()` |
| `backend/src/main/java/dev/tylercash/event/event/EventController.java` | Modify | Add `POST /{id}/recategorize` endpoint |
| `backend/src/test/java/dev/tylercash/event/event/EventControllerTest.java` | Modify | Tests for recategorize endpoint |
| `backend/src/test/java/dev/tylercash/event/event/EventServiceTest.java` | Modify | Update constructor calls to pass EmbeddingService mock |
| `frontend/src/lib/hooks.ts` | Modify | Add `recategorizeEvent()` function |
| `frontend/src/components/event/EventDetail.tsx` | Modify | Remove edit from admin panel; add recategorize button; hide writes for past events |

---

## Task 1: DB Migration — `event_classification_attempt` Table

**Files:**
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`

- [ ] **Step 1: Write the migration changeset**

Add this block at the end of `db.changelog-master.yaml` (before the `- include:` line for contract changelog):

```yaml
  - changeSet:
      id: create event_classification_attempt table
      author: tyler
      changes:
        - createTable:
            tableName: event_classification_attempt
            columns:
              - column:
                  name: event_id
                  type: UUID
                  constraints:
                    primaryKey: true
                    nullable: false
                    foreignKeyName: fk_classification_attempt_event
                    references: event(id)
              - column:
                  name: attempt_count
                  type: integer
                  defaultValueNumeric: 0
                  constraints:
                    nullable: false
              - column:
                  name: first_attempt_at
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
              - column:
                  name: next_retry_at
                  type: TIMESTAMP WITH TIME ZONE
                  constraints:
                    nullable: false
```

- [ ] **Step 2: Start backend and verify migration runs**

```bash
cd backend && ./gradlew bootRun "--args=--spring.profiles.active=local,nonprod --spring.datasource.url=jdbc:postgresql://localhost:5432/peepbot --spring.datasource.username=peepbot --spring.datasource.password=peepbot --spring.devtools.restart.enabled=false --server.servlet.session.cookie.secure=false --spring.session.cookie.secure=false --dev.tylercash.cors.allowed-origins=http://localhost:5173" 2>&1 | grep -E "(Liquibase|classification_attempt|ERROR)" | head -20
```

Expected: `Liquibase: Successfully acquired change log lock` and no ERROR lines about the new table.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "chore(db): add event_classification_attempt table for categorization retry tracking"
```

---

## Task 2: Entity and Repository for Retry Tracking

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/event/EventClassificationAttempt.java`
- Create: `backend/src/main/java/dev/tylercash/event/db/repository/EventClassificationAttemptRepository.java`

- [ ] **Step 1: Create the JPA entity**

Create `backend/src/main/java/dev/tylercash/event/event/EventClassificationAttempt.java`:

```java
package dev.tylercash.event.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "event_classification_attempt")
@Getter
@Setter
@NoArgsConstructor
public class EventClassificationAttempt {

    @Id
    private UUID eventId;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private ZonedDateTime firstAttemptAt;

    @Column(nullable = false)
    private ZonedDateTime nextRetryAt;
}
```

- [ ] **Step 2: Create the repository**

Create `backend/src/main/java/dev/tylercash/event/db/repository/EventClassificationAttemptRepository.java`:

```java
package dev.tylercash.event.db.repository;

import dev.tylercash.event.event.EventClassificationAttempt;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface EventClassificationAttemptRepository extends JpaRepository<EventClassificationAttempt, UUID> {

    @Query("SELECT a FROM EventClassificationAttempt a WHERE a.nextRetryAt <= :now ORDER BY a.nextRetryAt ASC")
    List<EventClassificationAttempt> findDueForRetry(ZonedDateTime now, Pageable pageable);
}
```

- [ ] **Step 3: Run tests to confirm nothing is broken**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.*" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/event/EventClassificationAttempt.java \
        backend/src/main/java/dev/tylercash/event/db/repository/EventClassificationAttemptRepository.java
git commit -m "feat(categorize): add EventClassificationAttempt entity and repository"
```

---

## Task 3: EventRepository Query for Unclassified Planned Events

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/db/repository/EventRepository.java`

- [ ] **Step 1: Add the query method**

In `EventRepository.java`, add after `findAllByState`:

```java
@Query(
        value =
                """
            SELECT e.* FROM event e
            WHERE e.state = 'PLANNED'
            AND NOT EXISTS (
                SELECT 1 FROM event_category ec WHERE ec.event_id = e.id
            )
            """,
        nativeQuery = true)
Page<Event> findPlannedEventsWithoutCategory(Pageable pageable);
```

- [ ] **Step 2: Run tests**

```bash
cd backend && ./gradlew test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/db/repository/EventRepository.java
git commit -m "feat(categorize): add query to find planned events without a category"
```

---

## Task 4: CategorizationRetryService with Exponential Backoff

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/event/CategorizationRetryService.java`
- Create: `backend/src/test/java/dev/tylercash/event/event/CategorizationRetryServiceTest.java`

Backoff schedule: attempt 1→2 waits 5 min, 2→3 waits 15 min, 3→4 waits 45 min, 4+ waits 135 min. All retries stop once 3 hours have elapsed since the first attempt.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/dev/tylercash/event/event/CategorizationRetryServiceTest.java`:

```java
package dev.tylercash.event.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventClassificationAttemptRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.rewind.EmbeddingService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class CategorizationRetryServiceTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2025, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    private static Event event(UUID id) {
        Event e = new Event(0L, 0L, 0L, "Test", "creator", ZonedDateTime.now(), "desc");
        e.setId(id);
        e.setState(EventState.PLANNED);
        return e;
    }

    private CategorizationRetryService service(
            EventRepository eventRepo,
            EventClassificationAttemptRepository attemptRepo,
            EmbeddingService embeddingService) {
        Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        return new CategorizationRetryService(eventRepo, attemptRepo, embeddingService, clock);
    }

    @Test
    void newUnclassifiedEvent_createsAttemptAndCallsClassify() {
        UUID id = UUID.randomUUID();
        Event event = event(id);
        EventRepository eventRepo = mock(EventRepository.class);
        EventClassificationAttemptRepository attemptRepo = mock(EventClassificationAttemptRepository.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        when(eventRepo.findPlannedEventsWithoutCategory(any()))
                .thenReturn(new PageImpl<>(List.of(event)));
        when(attemptRepo.findById(id)).thenReturn(Optional.empty());

        service(eventRepo, attemptRepo, embeddingService).retryUnclassifiedEvents();

        verify(embeddingService).classifyEvent(event);
        verify(attemptRepo).save(any(EventClassificationAttempt.class));
    }

    @Test
    void eventDueForRetry_callsClassifyAndUpdatesAttempt() {
        UUID id = UUID.randomUUID();
        Event event = event(id);
        EventRepository eventRepo = mock(EventRepository.class);
        EventClassificationAttemptRepository attemptRepo = mock(EventClassificationAttemptRepository.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        EventClassificationAttempt attempt = new EventClassificationAttempt();
        attempt.setEventId(id);
        attempt.setAttemptCount(1);
        attempt.setFirstAttemptAt(NOW.minusMinutes(10));
        attempt.setNextRetryAt(NOW.minusMinutes(1)); // past due

        when(eventRepo.findPlannedEventsWithoutCategory(any()))
                .thenReturn(new PageImpl<>(List.of(event)));
        when(attemptRepo.findById(id)).thenReturn(Optional.of(attempt));

        service(eventRepo, attemptRepo, embeddingService).retryUnclassifiedEvents();

        verify(embeddingService).classifyEvent(event);
        verify(attemptRepo).save(attempt);
    }

    @Test
    void eventNotYetDueForRetry_skipsClassify() {
        UUID id = UUID.randomUUID();
        Event event = event(id);
        EventRepository eventRepo = mock(EventRepository.class);
        EventClassificationAttemptRepository attemptRepo = mock(EventClassificationAttemptRepository.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        EventClassificationAttempt attempt = new EventClassificationAttempt();
        attempt.setEventId(id);
        attempt.setAttemptCount(1);
        attempt.setFirstAttemptAt(NOW.minusMinutes(2));
        attempt.setNextRetryAt(NOW.plusMinutes(3)); // not yet due

        when(eventRepo.findPlannedEventsWithoutCategory(any()))
                .thenReturn(new PageImpl<>(List.of(event)));
        when(attemptRepo.findById(id)).thenReturn(Optional.of(attempt));

        service(eventRepo, attemptRepo, embeddingService).retryUnclassifiedEvents();

        verify(embeddingService, never()).classifyEvent(any());
    }

    @Test
    void eventPast3hWindow_skipsClassify() {
        UUID id = UUID.randomUUID();
        Event event = event(id);
        EventRepository eventRepo = mock(EventRepository.class);
        EventClassificationAttemptRepository attemptRepo = mock(EventClassificationAttemptRepository.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        EventClassificationAttempt attempt = new EventClassificationAttempt();
        attempt.setEventId(id);
        attempt.setAttemptCount(4);
        attempt.setFirstAttemptAt(NOW.minusHours(4)); // 4h ago — past 3h window
        attempt.setNextRetryAt(NOW.minusMinutes(1));

        when(eventRepo.findPlannedEventsWithoutCategory(any()))
                .thenReturn(new PageImpl<>(List.of(event)));
        when(attemptRepo.findById(id)).thenReturn(Optional.of(attempt));

        service(eventRepo, attemptRepo, embeddingService).retryUnclassifiedEvents();

        verify(embeddingService, never()).classifyEvent(any());
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.event.CategorizationRetryServiceTest" 2>&1 | tail -20
```

Expected: FAIL — `CategorizationRetryService` does not exist yet.

- [ ] **Step 3: Implement CategorizationRetryService**

Create `backend/src/main/java/dev/tylercash/event/event/CategorizationRetryService.java`:

```java
package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.EventClassificationAttemptRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.rewind.EmbeddingService;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CategorizationRetryService {

    static final Duration MAX_RETRY_WINDOW = Duration.ofHours(3);
    static final int[] BACKOFF_MINUTES = {5, 15, 45, 135};

    private final EventRepository eventRepository;
    private final EventClassificationAttemptRepository attemptRepository;
    private final EmbeddingService embeddingService;
    private final Clock clock;

    @Scheduled(fixedRate = 60000)
    @SchedulerLock(name = "categorizationRetry")
    public void retryUnclassifiedEvents() {
        Page<Event> events = eventRepository.findPlannedEventsWithoutCategory(Pageable.ofSize(100));
        if (events.isEmpty()) return;

        log.info("Categorization retry: {} PLANNED events without category", events.getTotalElements());
        ZonedDateTime now = ZonedDateTime.now(clock);

        for (Event event : events) {
            try {
                processEvent(event, now);
            } catch (Exception e) {
                log.error("Error in categorization retry for '{}'", event.getName(), e);
            }
        }
    }

    private void processEvent(Event event, ZonedDateTime now) {
        Optional<EventClassificationAttempt> attemptOpt = attemptRepository.findById(event.getId());

        if (attemptOpt.isEmpty()) {
            log.info("First categorization retry attempt for '{}'", event.getName());
            tryClassify(event);
            createAttemptRecord(event, now, 0);
            return;
        }

        EventClassificationAttempt attempt = attemptOpt.get();

        if (attempt.getFirstAttemptAt().plus(MAX_RETRY_WINDOW).isBefore(now)) {
            log.warn("Categorization abandoned for '{}' — 3h window expired after {} attempts",
                    event.getName(), attempt.getAttemptCount());
            return;
        }

        if (attempt.getNextRetryAt().isAfter(now)) {
            return;
        }

        log.info("Retrying categorization for '{}' (attempt {})", event.getName(), attempt.getAttemptCount() + 1);
        tryClassify(event);
        updateAttemptRecord(attempt, now);
    }

    private void tryClassify(Event event) {
        try {
            embeddingService.classifyEvent(event);
        } catch (Exception e) {
            log.warn("Categorization attempt failed for '{}'", event.getName(), e);
        }
    }

    private void createAttemptRecord(Event event, ZonedDateTime now, int completedAttempts) {
        EventClassificationAttempt attempt = new EventClassificationAttempt();
        attempt.setEventId(event.getId());
        attempt.setAttemptCount(1);
        attempt.setFirstAttemptAt(now);
        attempt.setNextRetryAt(now.plusMinutes(backoffMinutes(completedAttempts)));
        attemptRepository.save(attempt);
    }

    private void updateAttemptRecord(EventClassificationAttempt attempt, ZonedDateTime now) {
        int newCount = attempt.getAttemptCount() + 1;
        attempt.setAttemptCount(newCount);
        attempt.setNextRetryAt(now.plusMinutes(backoffMinutes(newCount - 1)));
        attemptRepository.save(attempt);
    }

    private int backoffMinutes(int attemptIndex) {
        return BACKOFF_MINUTES[Math.min(attemptIndex, BACKOFF_MINUTES.length - 1)];
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.event.CategorizationRetryServiceTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, 4 tests passing.

- [ ] **Step 5: Run all tests**

```bash
cd backend && ./gradlew test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/event/CategorizationRetryService.java \
        backend/src/test/java/dev/tylercash/event/event/CategorizationRetryServiceTest.java
git commit -m "feat(categorize): add exponential backoff retry for PLANNED events with unknown category"
```

---

## Task 5: Manual Recategorize Endpoint

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/event/EventService.java`
- Modify: `backend/src/main/java/dev/tylercash/event/event/EventController.java`
- Modify: `backend/src/test/java/dev/tylercash/event/event/EventServiceTest.java`
- Modify: `backend/src/test/java/dev/tylercash/event/event/EventControllerTest.java`

- [ ] **Step 1: Write failing test for EventService.recategorizeEvent**

Add to `EventServiceTest.java`:

```java
import dev.tylercash.event.rewind.EmbeddingService;

// Update the createService helper to accept EmbeddingService:
private EventService createService(
        EventRepository eventRepository,
        DiscordService discordService,
        AttendanceService attendanceService,
        DiscordUserCacheService discordUserCacheService,
        EmbeddingService embeddingService) {
    return new EventService(
            discordService,
            eventRepository,
            mock(EventStateMachineService.class),
            Clock.systemDefaultZone(),
            attendanceService,
            discordUserCacheService,
            embeddingService);
}

@Test
void recategorizeEvent_callsEmbeddingServiceClassify() {
    EventRepository eventRepository = mock(EventRepository.class);
    EmbeddingService embeddingService = mock(EmbeddingService.class);
    EventService service = createService(
            eventRepository,
            mock(DiscordService.class),
            mock(AttendanceService.class),
            mock(DiscordUserCacheService.class),
            embeddingService);

    Event event = buildEvent();
    UUID id = UUID.randomUUID();
    event.setId(id);
    when(eventRepository.findById(id)).thenReturn(Optional.of(event));

    service.recategorizeEvent(id);

    verify(embeddingService).classifyEvent(event);
}
```

Also update all existing calls to the old 6-arg `createService` in `EventServiceTest.java` to use the new 7-arg version, passing `mock(EmbeddingService.class)` as the last argument.

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.event.EventServiceTest" 2>&1 | tail -20
```

Expected: compile error — `recategorizeEvent` does not exist yet.

- [ ] **Step 3: Add EmbeddingService to EventService and implement recategorizeEvent**

In `EventService.java`, add the import and field:

```java
import dev.tylercash.event.rewind.EmbeddingService;
```

Add to the field list (after existing fields):

```java
private final EmbeddingService embeddingService;
```

Add the method at the end of the class (before the closing brace):

```java
@CacheEvict(value = "eventDetail", key = "#id")
@Observed(name = "event.recategorize")
public void recategorizeEvent(UUID id) {
    MDC.put("eventId", id.toString());
    log.info("Recategorizing event id={}", id);
    Event event = getEvent(id);
    embeddingService.classifyEvent(event);
}
```

- [ ] **Step 4: Run EventService tests to confirm they pass**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.event.EventServiceTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Write failing test for the recategorize endpoint**

Add to `EventControllerTest.java` (find the `setupContext()` method and update `EventController` constructor if the test constructs it directly — it already uses `new EventController(eventService, discordService, attendanceService, discordUserCacheService, guildMembershipService)` which doesn't change):

```java
@Test
void recategorizeEvent_adminUser_callsServiceAndReturnsMessage() {
    EventControllerTestContext ctx = setupContext();
    UUID id = UUID.randomUUID();
    Event event = new Event(0L, 0L, GUILD_ID, "Test Event", DISCORD_ID, ZonedDateTime.now(), "desc");
    event.setId(id);

    when(ctx.eventService().getEvent(id)).thenReturn(event);
    when(ctx.discordService().isUserAdminOfServer(GUILD_ID, Long.parseLong(DISCORD_ID))).thenReturn(true);

    Map<String, String> result = ctx.controller().recategorizeEvent(id, ctx.principal());

    assertThat(result.get("message")).isEqualTo("Recategorization triggered");
    verify(ctx.eventService()).recategorizeEvent(id);
}

@Test
void recategorizeEvent_nonAdmin_throwsForbidden() {
    EventControllerTestContext ctx = setupContext();
    UUID id = UUID.randomUUID();
    Event event = new Event(0L, 0L, GUILD_ID, "Test Event", DISCORD_ID, ZonedDateTime.now(), "desc");
    event.setId(id);

    when(ctx.eventService().getEvent(id)).thenReturn(event);
    when(ctx.discordService().isUserAdminOfServer(GUILD_ID, Long.parseLong(DISCORD_ID))).thenReturn(false);

    assertThatThrownBy(() -> ctx.controller().recategorizeEvent(id, ctx.principal()))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
}
```

- [ ] **Step 6: Run test to confirm it fails**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.event.EventControllerTest" 2>&1 | tail -20
```

Expected: compile error — `recategorizeEvent` method does not exist in EventController.

- [ ] **Step 7: Add recategorize endpoint to EventController**

Add these imports if not already present (they're already all there):

Add the endpoint method in `EventController.java`:

```java
@Operation(summary = "Re-categorize an event", description = "Admin-only: triggers a fresh categorization attempt for this event")
@ApiResponses({
    @ApiResponse(responseCode = "200", description = "Recategorization triggered"),
    @ApiResponse(responseCode = "403", description = "Admin role required"),
    @ApiResponse(responseCode = "404", description = "Event not found")
})
@PostMapping(path = "/{id}/recategorize")
public Map<String, String> recategorizeEvent(
        @PathVariable UUID id, @AuthenticationPrincipal OAuth2User principal) {
    String discordId = principal.getAttribute("id");
    log.info("User {} triggering recategorization for event id={}", discordId, id);
    Event event = eventService.getEvent(id);
    if (!discordService.isUserAdminOfServer(event.getServerId(), Long.parseLong(discordId))) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
    }
    eventService.recategorizeEvent(id);
    return Map.of("message", "Recategorization triggered");
}
```

- [ ] **Step 8: Run all backend tests**

```bash
cd backend && ./gradlew test 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Apply Spotless formatting**

```bash
cd backend && ./gradlew spotlessApply 2>&1 | tail -5
```

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/event/EventService.java \
        backend/src/main/java/dev/tylercash/event/event/EventController.java \
        backend/src/test/java/dev/tylercash/event/event/EventServiceTest.java \
        backend/src/test/java/dev/tylercash/event/event/EventControllerTest.java
git commit -m "feat(categorize): add POST /event/{id}/recategorize admin endpoint"
```

---

## Task 6: Frontend — Add `recategorizeEvent` Hook

**Files:**
- Modify: `frontend/src/lib/hooks.ts`

- [ ] **Step 1: Add the function**

In `frontend/src/lib/hooks.ts`, add after `createPrivateChannel`:

```typescript
export async function recategorizeEvent(guildId: string, eventId: number | string) {
  await apiFetch(`/event/${eventId}/recategorize`, { method: "POST" });
  await invalidateEvent(guildId, eventId);
}
```

- [ ] **Step 2: Run lint and format checks**

```bash
cd frontend && npm run lint && npm run format:check 2>&1 | tail -10
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/lib/hooks.ts
git commit -m "feat(categorize): add recategorizeEvent frontend hook"
```

---

## Task 7: Frontend — Admin Panel Cleanup

**Files:**
- Modify: `frontend/src/components/event/EventDetail.tsx`

Changes:
1. Remove the edit link from the admin panel (lines 322–328 — the `Link` to `/events/${id}/edit`)
2. Add a re-categorize button (always visible in admin panel, even for past events)
3. Wrap cancel and private channel buttons with `!isPast &&`

- [ ] **Step 1: Add the import and state for recategorize**

At the top of `EventDetail.tsx`, in the existing imports block, add `recategorizeEvent` to the hooks import:

```typescript
import {
  cancelEvent,
  createPrivateChannel,
  recategorizeEvent,
  removeAttendee,
  submitRsvp,
  useActiveGuild,
  useCurrentUser,
  useEvent,
} from "@/lib/hooks";
```

- [ ] **Step 2: Add isRecategorizing state and handler**

After the existing `const [showPrivateChannelModal, setShowPrivateChannelModal] = useState(false);` line, add:

```typescript
const [isRecategorizing, setIsRecategorizing] = useState(false);

const handleRecategorize = async () => {
  if (!guild) return;
  setIsRecategorizing(true);
  try {
    await recategorizeEvent(guild.id, id);
  } finally {
    setIsRecategorizing(false);
  }
};
```

- [ ] **Step 3: Update the admin panel JSX**

Replace the entire admin panel block (lines 317–347, the `{me?.admin && !isCancelled && (...)}` block) with:

```tsx
{me?.admin && !isCancelled && (
  <div className="rounded-[14px] border-[1.5px] border-ink bg-paper2 p-5 shadow-chunky-md flex flex-col gap-2">
    <span className="text-[11px] font-extrabold tracking-[0.18em] text-mute uppercase">
      admin
    </span>
    {!isPast && (
      <>
        <Chunky
          variant="paper"
          size="sm"
          className="w-full justify-center"
          onClick={() => setShowPrivateChannelModal(true)}
          disabled={data.hasPrivateChannel}
        >
          {data.hasPrivateChannel ? "🔒 private channel active" : "🔒 create private channel"}
        </Chunky>
        <Chunky
          variant="danger"
          size="sm"
          className="w-full justify-center"
          onClick={() => setShowCancelModal(true)}
        >
          ✕ cancel event
        </Chunky>
      </>
    )}
    <Chunky
      variant="paper"
      size="sm"
      className="w-full justify-center"
      onClick={handleRecategorize}
      disabled={isRecategorizing}
    >
      {isRecategorizing ? "🔄 categorizing…" : "🔄 re-categorize"}
    </Chunky>
  </div>
)}
```

- [ ] **Step 4: Run lint and format**

```bash
cd frontend && npm run lint:fix && npm run format 2>&1 | tail -10
```

Expected: no errors.

- [ ] **Step 5: Verify in browser**

Start the dev server:

```bash
cd frontend && npm run dev
```

Open `http://localhost:5173` and check an event as an admin user:
- Admin panel should NOT show the edit event button
- Admin panel should show "🔒 create private channel" and "✕ cancel event" ONLY for future events
- Admin panel should ALWAYS show "🔄 re-categorize" button
- Clicking re-categorize should call the API (check browser network tab for `POST /api/event/{id}/recategorize`)
- For a past event, admin panel should only show "🔄 re-categorize"

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/event/EventDetail.tsx frontend/src/lib/hooks.ts
git commit -m "feat(admin): remove edit from admin panel, add re-categorize button, hide writes for past events"
```

---

## Self-Review Checklist

### Spec Coverage

| Requirement | Task |
|-------------|------|
| Re-attempt categorization for PLANNED/unknown events with exponential backoff up to 3h | Tasks 1–4 |
| Admin panel button to manually trigger categorization | Tasks 5–7 |
| Remove edit event from admin panel | Task 7 |
| Hide all write operations if event is in the past | Task 7 |

### Gaps Addressed

- **Cache eviction on recategorize**: `@CacheEvict(value = "eventDetail", key = "#id")` in `EventService.recategorizeEvent` ensures the frontend sees fresh category data after re-categorization.
- **Retry record cleanup**: Not needed — `findPlannedEventsWithoutCategory` only returns events still in PLANNED state with no `event_category` row. Once classified, the event drops out of the query automatically.
- **Hero edit button**: The `!isPast && !isCancelled` edit button in the hero section (lines 187–195) is not in the admin panel and is intentionally left unchanged by this plan — only the admin panel edit link is removed per the requirement.
- **EventServiceTest constructor**: All existing `createService(...)` call sites in `EventServiceTest` must be updated to the new 7-arg signature with `mock(EmbeddingService.class)` — this is required in Task 5, Step 1.
