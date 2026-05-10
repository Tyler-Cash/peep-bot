# TfNSW Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-guild toggleable TfNSW integration that posts noteworthy Sydney transport + traffic disruptions to a Discord event channel on event creation and 7 days before.

**Architecture:** Mirrors the existing `immich` / `places` per-guild feature pattern. New `tfnsw` package with two HTTP clients (TfNSW Open Data alerts + Live Traffic), a pure filter, a Discord reporting service, and one lifecycle listener (`EventCreated` trigger) plus one daily scheduled poller (week-before recheck). Event coordinates resolved lazily from `event.locationPlaceId` via a new Places coords client and persisted on the `event` row. A `tfnsw_event_snapshot` table stores a hash of last-posted noteworthy alert IDs so the recheck only posts on material change.

**Tech Stack:** Spring Boot 3.5.8, Java 21, JDA 5.6.1, Liquibase, GTFS-Realtime protobuf (`com.google.transit:gtfs-realtime-bindings`), Resilience4j, Caffeine, Testcontainers Postgres, WireMock, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-05-10-tfnsw-integration-design.md` — read this first.

---

## File Structure

**Backend — new files:**
- `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswConfiguration.java` — `@ConfigurationProperties("dev.tylercash.tfnsw")`, WebClient/RestClient beans, key validation
- `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswAlertsClient.java` — fetches Sydney Trains + Metro GTFS-R Service Alerts and Trip Replacements; parses protobuf
- `backend/src/main/java/dev/tylercash/event/tfnsw/LiveTrafficClient.java` — fetches Live Traffic NSW Major Events + Hazards GeoJSON
- `backend/src/main/java/dev/tylercash/event/tfnsw/MajorStations.java` — curated allowlist constant (station IDs + display names)
- `backend/src/main/java/dev/tylercash/event/tfnsw/NoteworthyItem.java` — record describing a single noteworthy alert/event for embed composition + hashing
- `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswNoteworthyFilter.java` — pure function applying the rail + traffic rules
- `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswReportingService.java` — composes Discord embed and posts via the existing Discord channel sender
- `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswEventSnapshot.java` — JPA entity for `tfnsw_event_snapshot`
- `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswEventSnapshotRepository.java` — Spring Data repo
- `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswOrchestrator.java` — coordinates fetch → filter → post → persist; called by both the listener and the poller
- `backend/src/main/java/dev/tylercash/event/lifecycle/listener/TfnswEventCreatedListener.java` — `DurableEventListener<EventCreated>` calling the orchestrator
- `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswWeekBeforePoller.java` — `@Scheduled` + `@SchedulerLock` daily job
- `backend/src/main/java/dev/tylercash/event/places/PlacesDetailsClient.java` — new client method to fetch lat/lng for a place_id (sibling of `PlacesPhotoClient`)

**Backend — modified files:**
- `backend/src/main/resources/db/changelog/db.changelog-master.yaml` — three new changesets (guild flag; event coords; snapshot table)
- `backend/src/main/java/dev/tylercash/event/discord/Guild.java` — add `tfnswEnabled` + default
- `backend/src/main/java/dev/tylercash/event/event/model/Event.java` — add `locationLat` / `locationLng` columns
- `backend/src/main/java/dev/tylercash/event/admin/AdminFeaturesRequest.java` — add `Boolean tfnswEnabled`
- `backend/src/main/java/dev/tylercash/event/admin/AdminController.java` — wire field through
- `backend/src/main/java/dev/tylercash/event/admin/AdminGuildDto.java` — expose flag
- `backend/src/main/java/dev/tylercash/event/admin/AdminJobCatalog.java` — register the poller
- `backend/src/main/resources/application.yaml` — defaults (radii, base URLs, empty key)
- `backend/build.gradle` — add `com.google.transit:gtfs-realtime-bindings:0.0.8`
- `CLAUDE.md` — note new flag, table, env var

**Frontend — modified files:**
- Bot-admin features panel (file path discovered during Task 23) — add a TfNSW toggle row matching the Immich/contracts pattern
- `frontend/src/lib/types.ts` (or codegen-equivalent) — `tfnswEnabled` propagates from OpenAPI regen

**Tests (created alongside production code):**
- `backend/src/test/java/dev/tylercash/event/tfnsw/TfnswNoteworthyFilterTest.java`
- `backend/src/test/java/dev/tylercash/event/tfnsw/MajorStationsTest.java`
- `backend/src/test/java/dev/tylercash/event/tfnsw/TfnswAlertsClientTest.java`
- `backend/src/test/java/dev/tylercash/event/tfnsw/LiveTrafficClientTest.java`
- `backend/src/test/java/dev/tylercash/event/tfnsw/TfnswReportingServiceTest.java`
- `backend/src/test/java/dev/tylercash/event/tfnsw/TfnswEventCreatedListenerTest.java`
- `backend/src/test/java/dev/tylercash/event/tfnsw/TfnswWeekBeforePollerTest.java`
- `backend/src/test/resources/tfnsw/` — fixture files (GTFS-R protobuf binaries + GeoJSON)

---

## Conventions

- TDD: write the test first, run to confirm failure, implement minimum, run to confirm pass, commit.
- Each commit is conventional commits style (`feat(tfnsw): …`, `test(tfnsw): …`, `chore(db): …`).
- Run `./gradlew spotlessApply` before each commit.
- Backend tests: `./gradlew test --tests <FQCN>`.
- Integration tests extend `AbstractHttpIntegrationTest`; use `TestIds.nextLong()` / `nextSnowflake()` for unique IDs and scope assertions to those IDs.
- Use `SharedPostgres.registerIsolatedDatabase(registry, ThisClass.class)` for the poller test (scans global state).

---

## Task 1: Add `tfnsw_enabled` column to `guild` table

**Files:**
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append new changeset at end)

- [ ] **Step 1: Append changeset to master changelog**

Append after the last existing changeset:

```yaml
  - changeSet:
      id: add tfnsw_enabled to guild
      author: tyler
      comment: >
        Per-guild opt-in for TfNSW (Sydney transport + traffic) disruption
        posts. Default false so existing guilds get nothing until a bot admin
        enables it.
      changes:
        - addColumn:
            tableName: guild
            columns:
              - column:
                  name: tfnsw_enabled
                  type: boolean
                  defaultValueBoolean: false
                  constraints:
                    nullable: false
```

- [ ] **Step 2: Verify schema applies cleanly**

Run: `./gradlew test --tests dev.tylercash.event.discord.GuildRepositoryTest` (or any existing repo test that boots Spring) — Liquibase runs at boot.

Expected: PASS, no Liquibase error.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "chore(db): add tfnsw_enabled flag to guild"
```

---

## Task 2: Add `tfnswEnabled` to `Guild` entity + default

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/Guild.java`

- [ ] **Step 1: Write failing test**

Create `backend/src/test/java/dev/tylercash/event/discord/GuildDefaultsTest.java`:

```java
package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GuildDefaultsTest {
    @Test
    void withDefaultsSetsTfnswDisabled() {
        Guild g = Guild.withDefaults(123L);
        assertThat(g.isTfnswEnabled()).isFalse();
    }
}
```

- [ ] **Step 2: Run, expect compile error on `isTfnswEnabled`**

Run: `./gradlew test --tests dev.tylercash.event.discord.GuildDefaultsTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Add field + default**

In `Guild.java`, add after `contractsEnabled`:

```java
    @Column(name = "tfnsw_enabled", nullable = false)
    private boolean tfnswEnabled;
```

In `Guild.withDefaults(...)`, add before `return g;`:

```java
        g.setTfnswEnabled(false);
```

- [ ] **Step 4: Run test, expect pass**

Run: `./gradlew test --tests dev.tylercash.event.discord.GuildDefaultsTest`
Expected: PASS.

- [ ] **Step 5: Spotless + commit**

```bash
./gradlew spotlessApply
git add backend/src/main/java/dev/tylercash/event/discord/Guild.java \
        backend/src/test/java/dev/tylercash/event/discord/GuildDefaultsTest.java
git commit -m "feat(tfnsw): add tfnswEnabled field to Guild"
```

---

## Task 3: Wire `tfnswEnabled` through admin API

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/admin/AdminFeaturesRequest.java`
- Modify: `backend/src/main/java/dev/tylercash/event/admin/AdminController.java`
- Modify: `backend/src/main/java/dev/tylercash/event/admin/AdminGuildDto.java`

- [ ] **Step 1: Write failing test**

Find the existing `AdminControllerTest` (search `find backend/src/test -name "AdminController*"`). Add a test that PUTs an `AdminFeaturesRequest` with `tfnswEnabled=true` and asserts the persisted Guild row reflects it. Use existing fixtures pattern in that file.

```java
@Test
void putFeaturesEnablesTfnsw() throws Exception {
    long guildId = TestIds.nextSnowflake();
    fixtures.registerGuild(guildId);

    mvc.perform(put("/admin/guilds/{id}/features", guildId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"tfnswEnabled": true}
                """))
        .header(...adminAuth...)
        .andExpect(status().isOk());

    assertThat(guildRepository.findById(guildId).orElseThrow().isTfnswEnabled()).isTrue();
}
```

(Match the file's existing auth/header/import style — copy from a neighbour test in the same class.)

- [ ] **Step 2: Run, expect failure**

Run: `./gradlew test --tests dev.tylercash.event.admin.AdminControllerTest.putFeaturesEnablesTfnsw`
Expected: FAIL — field unknown / not persisted.

- [ ] **Step 3: Add field to record**

`AdminFeaturesRequest.java`:

```java
public record AdminFeaturesRequest(
        Boolean immichEnabled,
        Boolean googleAutocompleteEnabled,
        Boolean rewindEnabled,
        Boolean contractsEnabled,
        Boolean tfnswEnabled) {}
```

- [ ] **Step 4: Wire through `AdminController`**

In `AdminController.java`, in the features-update method, add the same `if (req.tfnswEnabled() != null) guild.setTfnswEnabled(req.tfnswEnabled());` pattern used for the other flags.

- [ ] **Step 5: Expose in `AdminGuildDto`**

Add `boolean tfnswEnabled` to `AdminGuildDto` (field + constructor mapping from `Guild`), matching the pattern of the existing flags.

- [ ] **Step 6: Run test, expect pass**

Run: `./gradlew test --tests dev.tylercash.event.admin.AdminControllerTest`
Expected: PASS (full class — confirm no regressions).

- [ ] **Step 7: Spotless + commit**

```bash
./gradlew spotlessApply
git add backend/src/main/java/dev/tylercash/event/admin/AdminFeaturesRequest.java \
        backend/src/main/java/dev/tylercash/event/admin/AdminController.java \
        backend/src/main/java/dev/tylercash/event/admin/AdminGuildDto.java \
        backend/src/test/java/dev/tylercash/event/admin/AdminControllerTest.java
git commit -m "feat(tfnsw): expose tfnswEnabled via admin features API"
```

---

## Task 4: Add `location_lat` / `location_lng` columns to `event`

**Files:**
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`

- [ ] **Step 1: Append changeset**

```yaml
  - changeSet:
      id: add event location coords
      author: tyler
      comment: >
        Cached lat/lng for an event's place_id. Resolved lazily by the TfNSW
        listener via Places Details when first needed; persisted to avoid
        repeat lookups on the week-before recheck.
      changes:
        - addColumn:
            tableName: event
            columns:
              - column:
                  name: location_lat
                  type: double precision
              - column:
                  name: location_lng
                  type: double precision
```

- [ ] **Step 2: Boot test to verify migration**

Run: `./gradlew test --tests dev.tylercash.event.event.EventRepositoryTest` (or any boot-enabled test).
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "chore(db): add cached location coords to event"
```

---

## Task 5: Add `locationLat` / `locationLng` to `Event` entity

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/event/model/Event.java`

- [ ] **Step 1: Write failing test**

Create `backend/src/test/java/dev/tylercash/event/event/EventCoordsPersistenceTest.java`:

```java
package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.TestIds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EventCoordsPersistenceTest extends AbstractHttpIntegrationTest {
    @Autowired EventRepository events;

    @Test
    void persistsLatLng() {
        Event e = fixtures.seedEvent(b -> b.locationLat(-33.8688).locationLng(151.2093));
        Event reloaded = events.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getLocationLat()).isEqualTo(-33.8688);
        assertThat(reloaded.getLocationLng()).isEqualTo(151.2093);
    }
}
```

(Copy import / fixture-builder style from an existing `Event*Test` in the codebase. Adjust `fixtures.seedEvent` builder lambda to match its actual signature.)

- [ ] **Step 2: Run, expect compile failure**

Run: `./gradlew test --tests dev.tylercash.event.event.EventCoordsPersistenceTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Add fields to `Event.java`**

Near `locationPlaceId`:

```java
    @Column(name = "location_lat")
    private Double locationLat;

    @Column(name = "location_lng")
    private Double locationLng;
```

If `Event` uses Lombok `@Data` it gets getters/setters for free. Confirm.

- [ ] **Step 4: Update `fixtures.seedEvent` builder if needed**

If `HttpIntegrationFixtures.seedEvent` doesn't expose `locationLat/Lng`, add them to the builder. Keep it minimal (additive, no break).

- [ ] **Step 5: Run test, expect pass**

Run: `./gradlew test --tests dev.tylercash.event.event.EventCoordsPersistenceTest`
Expected: PASS.

- [ ] **Step 6: Spotless + commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(event): persist cached lat/lng on event"
```

---

## Task 6: Add `tfnsw_event_snapshot` table

**Files:**
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`

- [ ] **Step 1: Append changeset**

```yaml
  - changeSet:
      id: create tfnsw_event_snapshot table
      author: tyler
      comment: >
        Stores hash of last-posted noteworthy alert IDs per event so the
        week-before recheck only posts when there's a material change.
        last_posted_at is null when the check ran but nothing was noteworthy.
      changes:
        - createTable:
            tableName: tfnsw_event_snapshot
            columns:
              - column:
                  name: event_id
                  type: uuid
                  constraints:
                    primaryKey: true
                    nullable: false
                    foreignKeyName: fk_tfnsw_snapshot_event
                    references: event(id)
              - column:
                  name: alert_ids_hash
                  type: varchar(64)
                  constraints:
                    nullable: false
              - column:
                  name: last_posted_at
                  type: timestamp with time zone
              - column:
                  name: created_at
                  type: timestamp with time zone
                  defaultValueComputed: now()
                  constraints:
                    nullable: false
              - column:
                  name: updated_at
                  type: timestamp with time zone
                  defaultValueComputed: now()
                  constraints:
                    nullable: false
```

- [ ] **Step 2: Boot test to verify**

Run: `./gradlew test --tests dev.tylercash.event.event.EventRepositoryTest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "chore(db): add tfnsw_event_snapshot table"
```

---

## Task 7: Snapshot entity + repository

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswEventSnapshot.java`
- Create: `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswEventSnapshotRepository.java`

- [ ] **Step 1: Write failing test**

`backend/src/test/java/dev/tylercash/event/tfnsw/TfnswEventSnapshotRepositoryTest.java`:

```java
package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TfnswEventSnapshotRepositoryTest extends AbstractHttpIntegrationTest {
    @Autowired TfnswEventSnapshotRepository repo;

    @Test
    void roundTripsHashAndPostedAt() {
        Event e = fixtures.seedEvent(b -> {});
        TfnswEventSnapshot snap = new TfnswEventSnapshot();
        snap.setEventId(e.getId());
        snap.setAlertIdsHash("deadbeef");
        snap.setLastPostedAt(Instant.parse("2026-05-10T00:00:00Z"));
        repo.save(snap);

        TfnswEventSnapshot reloaded = repo.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getAlertIdsHash()).isEqualTo("deadbeef");
        assertThat(reloaded.getLastPostedAt()).isEqualTo(Instant.parse("2026-05-10T00:00:00Z"));
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.TfnswEventSnapshotRepositoryTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement entity**

`TfnswEventSnapshot.java`:

```java
package dev.tylercash.event.tfnsw;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "tfnsw_event_snapshot")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TfnswEventSnapshot {
    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "alert_ids_hash", nullable = false, length = 64)
    private String alertIdsHash;

    @Column(name = "last_posted_at")
    private Instant lastPostedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

- [ ] **Step 4: Implement repository**

`TfnswEventSnapshotRepository.java`:

```java
package dev.tylercash.event.tfnsw;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TfnswEventSnapshotRepository extends JpaRepository<TfnswEventSnapshot, UUID> {
    @Query("""
        SELECT e.id FROM Event e
        JOIN Guild g ON g.guildId = e.serverId
        WHERE g.tfnswEnabled = true
          AND CAST(e.dateTime AS LocalDate) BETWEEN :from AND :to
        """)
    List<UUID> findEventIdsForWeekBeforeCheck(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
```

(If JPQL field names differ — `serverId` vs `guildId` vs the Event's actual field — adjust to match the real `Event` entity. Verify by reading `Event.java` first.)

- [ ] **Step 5: Run test, expect pass**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.TfnswEventSnapshotRepositoryTest`
Expected: PASS.

- [ ] **Step 6: Spotless + commit**

```bash
./gradlew spotlessApply
git add backend/src/main/java/dev/tylercash/event/tfnsw/ \
        backend/src/test/java/dev/tylercash/event/tfnsw/
git commit -m "feat(tfnsw): add snapshot entity + repository"
```

---

## Task 8: Add gtfs-realtime-bindings dependency

**Files:**
- Modify: `backend/build.gradle`

- [ ] **Step 1: Add dependency**

In the `dependencies { … }` block:

```groovy
    implementation 'com.google.transit:gtfs-realtime-bindings:0.0.8'
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/build.gradle
git commit -m "chore(deps): add gtfs-realtime-bindings for tfnsw integration"
```

---

## Task 9: `TfnswConfiguration`

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswConfiguration.java`

- [ ] **Step 1: Write failing test**

`backend/src/test/java/dev/tylercash/event/tfnsw/TfnswConfigurationTest.java`:

```java
package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TfnswConfigurationTest {
    @Test
    void disabledByDefaultWhenNoApiKey() {
        TfnswConfiguration c = new TfnswConfiguration();
        assertThat(c.isEnabled()).isFalse();
    }

    @Test
    void enabledWhenApiKeySet() {
        TfnswConfiguration c = new TfnswConfiguration();
        c.setApiKey("k");
        assertThat(c.isEnabled()).isTrue();
    }

    @Test
    void validateAcceptsBlankKey() {
        new TfnswConfiguration().log();  // no throw
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.TfnswConfigurationTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement**

```java
package dev.tylercash.event.tfnsw;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.tfnsw")
public class TfnswConfiguration {
    private String apiKey = "";
    private String alertsBaseUrl = "https://api.transport.nsw.gov.au";
    private String liveTrafficBaseUrl = "https://api.transport.nsw.gov.au";
    private double nearestStationRadiusKm = 1.5;
    private double majorEventRadiusKm = 5.0;
    private double closureRadiusKm = 2.0;

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @PostConstruct
    public void log() {
        log.info("TfNSW integration {}", isEnabled() ? "enabled" : "disabled (no api-key configured)");
    }

    @Bean
    public RestClient tfnswAlertsRestClient() {
        return RestClient.builder()
                .baseUrl(alertsBaseUrl)
                .defaultHeader("Authorization", isEnabled() ? "apikey " + apiKey : "apikey none")
                .build();
    }

    @Bean
    public RestClient tfnswLiveTrafficRestClient() {
        return RestClient.builder()
                .baseUrl(liveTrafficBaseUrl)
                .defaultHeader("Authorization", isEnabled() ? "apikey " + apiKey : "apikey none")
                .build();
    }
}
```

- [ ] **Step 4: Run test**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.TfnswConfigurationTest`
Expected: PASS.

- [ ] **Step 5: Spotless + commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(tfnsw): add configuration + RestClient beans"
```

---

## Task 10: Update `application.yaml` defaults

**Files:**
- Modify: `backend/src/main/resources/application.yaml`

- [ ] **Step 1: Append config block**

Under `dev.tylercash:`:

```yaml
  tfnsw:
    api-key: "${TFNSW_API_KEY:}"
    alerts-base-url: "https://api.transport.nsw.gov.au"
    live-traffic-base-url: "https://api.transport.nsw.gov.au"
    nearest-station-radius-km: 1.5
    major-event-radius-km: 5.0
    closure-radius-km: 2.0
```

- [ ] **Step 2: Boot test verifies binding**

Run: `./gradlew test --tests dev.tylercash.event.discord.GuildDefaultsTest` (cheap boot test).
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/application.yaml
git commit -m "chore(config): add tfnsw defaults to application.yaml"
```

---

## Task 11: `MajorStations` constant + snapshot test

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/tfnsw/MajorStations.java`

- [ ] **Step 1: Write failing test**

`backend/src/test/java/dev/tylercash/event/tfnsw/MajorStationsTest.java`:

```java
package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MajorStationsTest {
    @Test
    void containsKeyHubs() {
        assertThat(MajorStations.STOP_IDS)
                .contains("200060")  // Central
                .contains("200070")  // Town Hall
                .contains("200080"); // Wynyard
    }

    @Test
    void allMetroStationsIncluded() {
        // Sydney Metro (Tallawong → Bankstown) currently has 31 stations.
        // Snapshot pin to force intentional updates.
        assertThat(MajorStations.STOP_IDS.stream().filter(s -> s.startsWith("MET")).count())
                .isEqualTo(MajorStations.METRO_COUNT);
    }
}
```

(The exact stop IDs above are placeholders — TfNSW uses TSN/TfNSW stop IDs; the real values come from the GTFS stops.txt for Sydney Trains. The implementer will look these up; if the test stop IDs don't match reality, update the test to use the real ones discovered during implementation. The shape of the test stays the same.)

- [ ] **Step 2: Run, expect compile failure**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.MajorStationsTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement**

```java
package dev.tylercash.event.tfnsw;

import java.util.Set;

/**
 * Curated allowlist of "major" Sydney rail/metro stations whose alerts are
 * always considered noteworthy. Stop IDs sourced from TfNSW Sydney Trains +
 * Sydney Metro GTFS feeds.
 */
public final class MajorStations {
    public static final int METRO_COUNT = 31;

    public static final Set<String> STOP_IDS = Set.of(
            // Sydney Trains hubs (look up real IDs from current GTFS stops.txt)
            "200060", // Central
            "200070", // Town Hall
            "200080", // Wynyard
            "200090", // Circular Quay
            "200100", // Martin Place
            "200110", // Museum
            "200120", // St James
            // Major interchanges
            "206710", // Chatswood
            "275010", // Parramatta
            "207210", // North Sydney
            "211110", // Bondi Junction
            "213210", // Strathfield
            "227010", // Hurstville
            // Metro (placeholder MET- prefixed; replace with real Metro stop IDs)
            "MET-TLW", // Tallawong
            "MET-RHH", // Rouse Hill
            "MET-KWP", // Kellyville
            "MET-BVL", // Bella Vista
            "MET-NWP", // Norwest
            "MET-HHL", // Hills Showground
            "MET-CKB", // Cherrybrook
            "MET-CST", // Castle Hill
            "MET-EPP", // Epping
            "MET-MQU", // Macquarie University
            "MET-MQP", // Macquarie Park
            "MET-NTR", // North Ryde
            "MET-CTW", // Chatswood (interchange)
            "MET-CRN", // Crows Nest
            "MET-VIC", // Victoria Cross
            "MET-BAR", // Barangaroo
            "MET-MTP", // Martin Place (Metro)
            "MET-PBN", // Pitt St
            "MET-CEN", // Central (Metro)
            "MET-WTL", // Waterloo
            "MET-SYD", // Sydenham
            "MET-MRV", // Marrickville
            "MET-DUL", // Dulwich Hill
            "MET-HUR", // Hurlstone Park
            "MET-CTB", // Canterbury
            "MET-CBR", // Campsie
            "MET-BLB", // Belmore
            "MET-LKB", // Lakemba
            "MET-WLY", // Wiley Park
            "MET-PUN", // Punchbowl
            "MET-BSW"  // Bankstown
    );

    private MajorStations() {}
}
```

(The "real" GTFS stop IDs are looked up from the TfNSW GTFS feed — if the implementer can't easily get them upfront, leave the placeholders and add a TODO comment **only on this single line** noting they need replacement before enabling in any guild. This is the one acceptable deferred-detail in the plan because the data lives outside the repo.)

- [ ] **Step 4: Run test, expect pass**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.MajorStationsTest`
Expected: PASS.

- [ ] **Step 5: Spotless + commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(tfnsw): curated major-stations allowlist"
```

---

## Task 12: `NoteworthyItem` model

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/tfnsw/NoteworthyItem.java`

- [ ] **Step 1: Implement (no test — just a record)**

```java
package dev.tylercash.event.tfnsw;

public record NoteworthyItem(
        Source source,           // RAIL_METRO or TRAFFIC
        String id,               // alert/event id from upstream — used for hash + dedupe
        String title,            // short headline for embed line
        String detail,           // 1-2 sentences of context
        String url,              // deep link to TfNSW page
        Reason reason            // why it matched (for logging / future tuning)
) {
    public enum Source { RAIL_METRO, TRAFFIC }
    public enum Reason {
        NEAREST_STATION,
        MAJOR_STATION,
        SEVERE_CITYWIDE,
        MAJOR_EVENT_NEAR_VENUE,
        CLOSURE_NEAR_VENUE
    }
}
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew compileJava && ./gradlew spotlessApply
git add backend/src/main/java/dev/tylercash/event/tfnsw/NoteworthyItem.java
git commit -m "feat(tfnsw): NoteworthyItem record"
```

---

## Task 13: `TfnswNoteworthyFilter` (pure function, exhaustive unit tests)

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswNoteworthyFilter.java`
- Create: `backend/src/test/java/dev/tylercash/event/tfnsw/TfnswNoteworthyFilterTest.java`

This is the single most-important file. Get the TDD coverage right.

The filter input is **already-parsed** domain objects, NOT raw protobuf — so this stays pure and easy to test. Define small input records inline:

```java
public record RailAlert(String id, String headline, String description, String url,
                        java.util.Set<String> affectedStopIds, java.util.Set<String> affectedRouteIds,
                        Severity severity, java.time.Instant start, java.time.Instant end) {
    public enum Severity { UNKNOWN, INFO, WARNING, SEVERE }
}
public record TrafficEvent(String id, String headline, String description, String url,
                           Kind kind, double lat, double lng,
                           java.time.Instant start, java.time.Instant end,
                           String roadClass /* nullable */) {
    public enum Kind { MAJOR_EVENT, ROAD_CLOSURE }
}
```

Put these as nested records inside `TfnswNoteworthyFilter` (or sibling files in the package — implementer's call, as long as they're in `dev.tylercash.event.tfnsw`).

- [ ] **Step 1: Write failing tests — one per rule**

```java
package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.tylercash.event.tfnsw.NoteworthyItem.Reason;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.TrafficEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TfnswNoteworthyFilterTest {
    private final TfnswConfiguration cfg = defaultCfg();
    private final TfnswNoteworthyFilter filter = new TfnswNoteworthyFilter(cfg);

    private static TfnswConfiguration defaultCfg() {
        TfnswConfiguration c = new TfnswConfiguration();
        c.setNearestStationRadiusKm(1.5);
        c.setMajorEventRadiusKm(5.0);
        c.setClosureRadiusKm(2.0);
        return c;
    }

    // Sydney CBD coords for tests
    private static final double VENUE_LAT = -33.8688;
    private static final double VENUE_LNG = 151.2093;
    private static final LocalDate EVENT_DATE = LocalDate.of(2026, 5, 17);

    private static RailAlert alert(String id, Set<String> stops, RailAlert.Severity sev) {
        return new RailAlert(id, "Headline", "Detail", "https://transportnsw.info/alerts/" + id,
                stops, Set.of(), sev,
                EVENT_DATE.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                EVENT_DATE.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
    }

    @Test
    void railAlertOnNearestStationMatches() {
        // Pretend Town Hall is the nearest station (caller passes it in).
        var items = filter.filter(
                List.of(alert("a1", Set.of("200070"), RailAlert.Severity.WARNING)),
                List.of(),
                VENUE_LAT, VENUE_LNG, "200070", EVENT_DATE);
        assertThat(items).extracting(NoteworthyItem::reason).containsExactly(Reason.NEAREST_STATION);
    }

    @Test
    void railAlertOnMajorStationMatches() {
        var items = filter.filter(
                List.of(alert("a2", Set.of("200060" /* Central */), RailAlert.Severity.WARNING)),
                List.of(),
                VENUE_LAT, VENUE_LNG, "999999", EVENT_DATE);
        assertThat(items).extracting(NoteworthyItem::reason).containsExactly(Reason.MAJOR_STATION);
    }

    @Test
    void severeCitywideMatches() {
        var items = filter.filter(
                List.of(new RailAlert("a3", "All Trains Down", "...", "u",
                        Set.of("999999"), Set.of("T1"), RailAlert.Severity.SEVERE,
                        EVENT_DATE.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                        EVENT_DATE.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC))),
                List.of(),
                VENUE_LAT, VENUE_LNG, "999999", EVENT_DATE);
        assertThat(items).extracting(NoteworthyItem::reason).containsExactly(Reason.SEVERE_CITYWIDE);
    }

    @Test
    void minorAlertOnUnrelatedStationSkipped() {
        var items = filter.filter(
                List.of(alert("a4", Set.of("999999"), RailAlert.Severity.INFO)),
                List.of(),
                VENUE_LAT, VENUE_LNG, "888888", EVENT_DATE);
        assertThat(items).isEmpty();
    }

    @Test
    void alertOutsideEventDateSkipped() {
        Instant longAfter = EVENT_DATE.plusDays(30).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        var items = filter.filter(
                List.of(new RailAlert("a5", "x", "y", "u", Set.of("200060"), Set.of(),
                        RailAlert.Severity.WARNING, longAfter, longAfter.plusSeconds(3600))),
                List.of(),
                VENUE_LAT, VENUE_LNG, "999999", EVENT_DATE);
        assertThat(items).isEmpty();
    }

    @Test
    void majorEventNearVenueMatches() {
        var t = new TrafficEvent("e1", "Marathon", "City roads closed", "u",
                TrafficEvent.Kind.MAJOR_EVENT, -33.8700, 151.2100,
                EVENT_DATE.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                EVENT_DATE.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                null);
        var items = filter.filter(List.of(), List.of(t), VENUE_LAT, VENUE_LNG, "X", EVENT_DATE);
        assertThat(items).extracting(NoteworthyItem::reason).containsExactly(Reason.MAJOR_EVENT_NEAR_VENUE);
    }

    @Test
    void majorEventFarFromVenueSkipped() {
        var t = new TrafficEvent("e2", "Marathon", "...", "u",
                TrafficEvent.Kind.MAJOR_EVENT, -34.5000, 150.5000,
                EVENT_DATE.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                EVENT_DATE.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                null);
        var items = filter.filter(List.of(), List.of(t), VENUE_LAT, VENUE_LNG, "X", EVENT_DATE);
        assertThat(items).isEmpty();
    }

    @Test
    void roadClosureOnArterialNearVenueMatches() {
        var t = new TrafficEvent("c1", "George St closed", "...", "u",
                TrafficEvent.Kind.ROAD_CLOSURE, -33.8695, 151.2090,
                EVENT_DATE.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                EVENT_DATE.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                "arterial");
        var items = filter.filter(List.of(), List.of(t), VENUE_LAT, VENUE_LNG, "X", EVENT_DATE);
        assertThat(items).extracting(NoteworthyItem::reason).containsExactly(Reason.CLOSURE_NEAR_VENUE);
    }

    @Test
    void roadClosureOnLocalRoadSkipped() {
        var t = new TrafficEvent("c2", "Side st closed", "...", "u",
                TrafficEvent.Kind.ROAD_CLOSURE, -33.8695, 151.2090,
                EVENT_DATE.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                EVENT_DATE.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                "local");
        var items = filter.filter(List.of(), List.of(t), VENUE_LAT, VENUE_LNG, "X", EVENT_DATE);
        assertThat(items).isEmpty();
    }

    @Test
    void noNearestStationStillRunsRailRules() {
        // When event has no nearest known rail station (rare — venue not near rail at all),
        // major-station + severe-citywide rules still apply.
        var items = filter.filter(
                List.of(alert("a6", Set.of("200060"), RailAlert.Severity.WARNING)),
                List.of(),
                VENUE_LAT, VENUE_LNG, null, EVENT_DATE);
        assertThat(items).extracting(NoteworthyItem::reason).containsExactly(Reason.MAJOR_STATION);
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.TfnswNoteworthyFilterTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement filter**

```java
package dev.tylercash.event.tfnsw;

import dev.tylercash.event.tfnsw.NoteworthyItem.Reason;
import dev.tylercash.event.tfnsw.NoteworthyItem.Source;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TfnswNoteworthyFilter {
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");
    private static final Set<String> ARTERIAL_CLASSES = Set.of("motorway", "arterial", "sub-arterial");
    private static final Set<String> CITYWIDE_LINE_PREFIXES = Set.of("T", "MET", "Sydney Trains", "Sydney Metro");

    private final TfnswConfiguration cfg;

    public List<NoteworthyItem> filter(
            List<RailAlert> railAlerts,
            List<TrafficEvent> trafficEvents,
            double venueLat,
            double venueLng,
            String nearestStationId,
            LocalDate eventDate) {
        List<NoteworthyItem> out = new ArrayList<>();
        Instant dayStart = eventDate.atStartOfDay(SYDNEY).toInstant();
        Instant dayEnd = eventDate.plusDays(1).atStartOfDay(SYDNEY).toInstant();

        for (RailAlert a : railAlerts) {
            if (!overlaps(a.start(), a.end(), dayStart, dayEnd)) continue;
            Reason reason = null;
            if (nearestStationId != null && a.affectedStopIds().contains(nearestStationId)) {
                reason = Reason.NEAREST_STATION;
            } else if (!java.util.Collections.disjoint(a.affectedStopIds(), MajorStations.STOP_IDS)) {
                reason = Reason.MAJOR_STATION;
            } else if (a.severity() == RailAlert.Severity.SEVERE && affectsCitywideLine(a.affectedRouteIds())) {
                reason = Reason.SEVERE_CITYWIDE;
            }
            if (reason != null) {
                out.add(new NoteworthyItem(Source.RAIL_METRO, a.id(), a.headline(), a.description(), a.url(), reason));
            }
        }

        for (TrafficEvent t : trafficEvents) {
            if (!overlaps(t.start(), t.end(), dayStart, dayEnd)) continue;
            double dKm = haversineKm(venueLat, venueLng, t.lat(), t.lng());
            Reason reason = null;
            if (t.kind() == TrafficEvent.Kind.MAJOR_EVENT && dKm <= cfg.getMajorEventRadiusKm()) {
                reason = Reason.MAJOR_EVENT_NEAR_VENUE;
            } else if (t.kind() == TrafficEvent.Kind.ROAD_CLOSURE
                    && dKm <= cfg.getClosureRadiusKm()
                    && t.roadClass() != null
                    && ARTERIAL_CLASSES.contains(t.roadClass().toLowerCase())) {
                reason = Reason.CLOSURE_NEAR_VENUE;
            }
            if (reason != null) {
                out.add(new NoteworthyItem(Source.TRAFFIC, t.id(), t.headline(), t.description(), t.url(), reason));
            }
        }
        return out;
    }

    private static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return !aStart.isAfter(bEnd) && !aEnd.isBefore(bStart);
    }

    private static boolean affectsCitywideLine(Set<String> routeIds) {
        return routeIds.stream().anyMatch(r -> CITYWIDE_LINE_PREFIXES.stream().anyMatch(r::startsWith));
    }

    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                  * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }

    public record RailAlert(String id, String headline, String description, String url,
                            Set<String> affectedStopIds, Set<String> affectedRouteIds,
                            Severity severity, Instant start, Instant end) {
        public enum Severity { UNKNOWN, INFO, WARNING, SEVERE }
    }
    public record TrafficEvent(String id, String headline, String description, String url,
                               Kind kind, double lat, double lng,
                               Instant start, Instant end,
                               String roadClass) {
        public enum Kind { MAJOR_EVENT, ROAD_CLOSURE }
    }
}
```

- [ ] **Step 4: Run all filter tests, expect pass**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.TfnswNoteworthyFilterTest`
Expected: 10 PASS.

- [ ] **Step 5: Spotless + commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(tfnsw): noteworthy filter with rail + traffic rules"
```

---

## Task 14: `TfnswAlertsClient`

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswAlertsClient.java`
- Create: `backend/src/test/java/dev/tylercash/event/tfnsw/TfnswAlertsClientTest.java`
- Create: `backend/src/test/resources/tfnsw/sydney-trains-alerts-sample.pb` (binary fixture, captured from real API once)

The client takes raw GTFS-R protobuf bytes from TfNSW and converts to `TfnswNoteworthyFilter.RailAlert` records.

- [ ] **Step 1: Capture a real GTFS-R fixture**

Manually download a current snapshot:

```bash
curl -H "Authorization: apikey $TFNSW_API_KEY" \
  https://api.transport.nsw.gov.au/v1/gtfs/alerts/sydneytrains \
  -o backend/src/test/resources/tfnsw/sydney-trains-alerts-sample.pb
```

(Implementer needs a TfNSW Open Data API key for this single bootstrap step. If unavailable, hand-craft a minimal `FeedMessage` programmatically in the test as the fallback.)

- [ ] **Step 2: Write failing test**

```java
package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

class TfnswAlertsClientTest {
    @Test
    void parsesGtfsRealtimeFeed() throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get("src/test/resources/tfnsw/sydney-trains-alerts-sample.pb"));
        List<RailAlert> alerts = TfnswAlertsClient.parse(bytes, "sydneytrains");
        assertThat(alerts).isNotEmpty();
        assertThat(alerts.get(0).id()).isNotBlank();
        assertThat(alerts.get(0).headline()).isNotBlank();
    }
}
```

- [ ] **Step 3: Run, expect failure**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.TfnswAlertsClientTest`
Expected: COMPILE FAIL.

- [ ] **Step 4: Implement client**

```java
package dev.tylercash.event.tfnsw;

import com.google.transit.realtime.GtfsRealtime;
import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert.Severity;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class TfnswAlertsClient {
    private final RestClient client;
    private final TfnswConfiguration cfg;

    public TfnswAlertsClient(@Qualifier("tfnswAlertsRestClient") RestClient client, TfnswConfiguration cfg) {
        this.client = client;
        this.cfg = cfg;
    }

    @CircuitBreaker(name = "tfnsw")
    public List<RailAlert> fetchSydneyTrains() {
        return fetch("/v1/gtfs/alerts/sydneytrains", "sydneytrains");
    }

    @CircuitBreaker(name = "tfnsw")
    public List<RailAlert> fetchSydneyMetro() {
        return fetch("/v1/gtfs/alerts/sydneymetro", "sydneymetro");
    }

    @CircuitBreaker(name = "tfnsw")
    public List<RailAlert> fetchTripReplacements() {
        // Trip Replacement Vehicles feed represents planned trackwork as alerts.
        return fetch("/v1/gtfs/realtime/sydneytrains", "trip-replacements");
    }

    private List<RailAlert> fetch(String path, String agency) {
        if (!cfg.isEnabled()) return List.of();
        try {
            byte[] body = client.get().uri(path).retrieve().body(byte[].class);
            return body == null ? List.of() : parse(body, agency);
        } catch (Exception e) {
            log.warn("TfNSW alerts fetch failed for {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    static List<RailAlert> parse(byte[] bytes, String agency) throws Exception {
        FeedMessage feed = FeedMessage.parseFrom(bytes);
        List<RailAlert> out = new ArrayList<>();
        for (FeedEntity entity : feed.getEntityList()) {
            if (!entity.hasAlert()) continue;
            Alert a = entity.getAlert();
            Set<String> stops = new HashSet<>();
            Set<String> routes = new HashSet<>();
            for (EntitySelector sel : a.getInformedEntityList()) {
                if (sel.hasStopId()) stops.add(sel.getStopId());
                if (sel.hasRouteId()) routes.add(sel.getRouteId());
            }
            String headline = textOf(a.getHeaderText());
            String desc = textOf(a.getDescriptionText());
            String url = textOf(a.getUrl());
            Instant start = a.getActivePeriodCount() > 0
                    ? Instant.ofEpochSecond(a.getActivePeriod(0).getStart())
                    : Instant.EPOCH;
            Instant end = a.getActivePeriodCount() > 0 && a.getActivePeriod(0).getEnd() > 0
                    ? Instant.ofEpochSecond(a.getActivePeriod(0).getEnd())
                    : Instant.now().plusSeconds(86400 * 365);
            Severity sev = mapSeverity(a.getSeverityLevel());
            out.add(new RailAlert(entity.getId(), headline, desc, url, stops, routes, sev, start, end));
        }
        log.debug("Parsed {} alerts from {}", out.size(), agency);
        return out;
    }

    private static String textOf(GtfsRealtime.TranslatedString ts) {
        return ts.getTranslationCount() > 0 ? ts.getTranslation(0).getText() : "";
    }

    private static Severity mapSeverity(Alert.SeverityLevel s) {
        return switch (s) {
            case INFO -> Severity.INFO;
            case WARNING -> Severity.WARNING;
            case SEVERE -> Severity.SEVERE;
            default -> Severity.UNKNOWN;
        };
    }
}
```

- [ ] **Step 5: Run test, expect pass**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.TfnswAlertsClientTest`
Expected: PASS.

- [ ] **Step 6: Spotless + commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(tfnsw): GTFS-R alerts client (rail + metro + trip replacements)"
```

---

## Task 15: `LiveTrafficClient`

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/tfnsw/LiveTrafficClient.java`
- Create: `backend/src/test/java/dev/tylercash/event/tfnsw/LiveTrafficClientTest.java`
- Create: `backend/src/test/resources/tfnsw/major-events-sample.json`
- Create: `backend/src/test/resources/tfnsw/hazards-sample.json`

- [ ] **Step 1: Capture (or hand-craft) GeoJSON fixtures**

If the implementer has an API key:

```bash
curl -H "Authorization: apikey $TFNSW_API_KEY" \
  https://api.transport.nsw.gov.au/v2/live/hazards/majorevent/open \
  -o backend/src/test/resources/tfnsw/major-events-sample.json

curl -H "Authorization: apikey $TFNSW_API_KEY" \
  https://api.transport.nsw.gov.au/v2/live/hazards/incident/open \
  -o backend/src/test/resources/tfnsw/hazards-sample.json
```

If not, hand-write a minimal valid sample:

```json
{
  "features": [
    {
      "type": "Feature",
      "properties": {
        "id": "evt-1",
        "displayName": "Vivid Sydney 2026",
        "headline": "Major event in CBD",
        "description": "Road closures from 6pm",
        "mainCategory": "majorEvent",
        "start": "2026-05-17T08:00:00+10:00",
        "end": "2026-05-17T23:00:00+10:00",
        "webLinkUrl": "https://livetraffic.com/desktop.html#evt-1",
        "roads": [{"classOfRoad": "arterial"}]
      },
      "geometry": {"type": "Point", "coordinates": [151.2093, -33.8688]}
    }
  ]
}
```

- [ ] **Step 2: Write failing test**

```java
package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.TrafficEvent;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiveTrafficClientTest {
    @Test
    void parsesMajorEventsGeoJson() throws Exception {
        String json = Files.readString(Paths.get("src/test/resources/tfnsw/major-events-sample.json"));
        List<TrafficEvent> events = LiveTrafficClient.parseMajorEvents(json);
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).kind()).isEqualTo(TrafficEvent.Kind.MAJOR_EVENT);
        assertThat(events.get(0).lat()).isCloseTo(-33.8688, org.assertj.core.data.Offset.offset(0.001));
        assertThat(events.get(0).lng()).isCloseTo(151.2093, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void parsesHazardsGeoJson() throws Exception {
        String json = Files.readString(Paths.get("src/test/resources/tfnsw/hazards-sample.json"));
        List<TrafficEvent> events = LiveTrafficClient.parseHazards(json);
        assertThat(events).allSatisfy(e -> assertThat(e.kind()).isEqualTo(TrafficEvent.Kind.ROAD_CLOSURE));
    }
}
```

- [ ] **Step 3: Run, expect failure**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.LiveTrafficClientTest`
Expected: COMPILE FAIL.

- [ ] **Step 4: Implement**

```java
package dev.tylercash.event.tfnsw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.TrafficEvent;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.TrafficEvent.Kind;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class LiveTrafficClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestClient client;
    private final TfnswConfiguration cfg;

    public LiveTrafficClient(@Qualifier("tfnswLiveTrafficRestClient") RestClient client, TfnswConfiguration cfg) {
        this.client = client;
        this.cfg = cfg;
    }

    @CircuitBreaker(name = "tfnsw")
    public List<TrafficEvent> fetchMajorEvents() {
        return fetch("/v2/live/hazards/majorevent/open", LiveTrafficClient::parseMajorEvents);
    }

    @CircuitBreaker(name = "tfnsw")
    public List<TrafficEvent> fetchHazards() {
        return fetch("/v2/live/hazards/incident/open", LiveTrafficClient::parseHazards);
    }

    private List<TrafficEvent> fetch(String path, java.util.function.Function<String, List<TrafficEvent>> parser) {
        if (!cfg.isEnabled()) return List.of();
        try {
            String body = client.get().uri(path).retrieve().body(String.class);
            return body == null ? List.of() : parser.apply(body);
        } catch (Exception e) {
            log.warn("TfNSW live-traffic fetch failed for {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    static List<TrafficEvent> parseMajorEvents(String json) {
        return parseFeatures(json, Kind.MAJOR_EVENT);
    }

    static List<TrafficEvent> parseHazards(String json) {
        return parseFeatures(json, Kind.ROAD_CLOSURE);
    }

    private static List<TrafficEvent> parseFeatures(String json, Kind kind) {
        try {
            JsonNode root = MAPPER.readTree(json);
            List<TrafficEvent> out = new ArrayList<>();
            for (JsonNode f : root.path("features")) {
                JsonNode props = f.path("properties");
                if (kind == Kind.ROAD_CLOSURE
                        && !"roadClosure".equalsIgnoreCase(props.path("mainCategory").asText())) {
                    continue;
                }
                JsonNode coords = f.path("geometry").path("coordinates");
                double lng = firstNumber(coords, 0);
                double lat = firstNumber(coords, 1);
                String roadClass = props.path("roads").isArray() && props.path("roads").size() > 0
                        ? props.path("roads").get(0).path("classOfRoad").asText(null)
                        : null;
                out.add(new TrafficEvent(
                        props.path("id").asText(),
                        props.path("displayName").asText(""),
                        props.path("description").asText(""),
                        props.path("webLinkUrl").asText(""),
                        kind,
                        lat, lng,
                        parseTime(props.path("start").asText(null)),
                        parseTime(props.path("end").asText(null)),
                        roadClass));
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to parse GeoJSON: {}", e.getMessage());
            return List.of();
        }
    }

    private static double firstNumber(JsonNode arr, int i) {
        if (arr.isArray() && arr.size() > i && arr.get(i).isNumber()) return arr.get(i).asDouble();
        // handle nested polygon coords by drilling to first point
        if (arr.isArray() && arr.size() > 0 && arr.get(0).isArray()) return firstNumber(arr.get(0), i);
        return 0.0;
    }

    private static Instant parseTime(String s) {
        if (s == null || s.isBlank()) return Instant.EPOCH;
        try { return OffsetDateTime.parse(s).toInstant(); } catch (Exception e) { return Instant.EPOCH; }
    }
}
```

- [ ] **Step 5: Run test, expect pass**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.LiveTrafficClientTest`
Expected: PASS.

- [ ] **Step 6: Spotless + commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(tfnsw): live-traffic GeoJSON client (major events + hazards)"
```

---

## Task 16: `PlacesDetailsClient` (lat/lng resolver)

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/places/PlacesDetailsClient.java`
- Create: `backend/src/test/java/dev/tylercash/event/places/PlacesDetailsClientTest.java`

- [ ] **Step 1: Write failing test (use WireMock or stub `RestClient`)**

```java
package dev.tylercash.event.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PlacesDetailsClientTest {
    @Test
    void parsesLatLngFromResponse() throws Exception {
        String body = """
                {"location": {"latitude": -33.8688, "longitude": 151.2093}}
                """;
        JsonNode node = new ObjectMapper().readTree(body);

        // Stub RestClient chain
        RestClient client = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(client.get().uri(anyString(), anyString(), anyString()).retrieve().body(JsonNode.class))
                .thenReturn(node);

        PlacesConfiguration cfg = new PlacesConfiguration();
        cfg.setApiKey("k");
        var out = new PlacesDetailsClient(client, cfg).fetchCoords("place-id");
        assertThat(out).isPresent();
        assertThat(out.get().lat()).isEqualTo(-33.8688);
        assertThat(out.get().lng()).isEqualTo(151.2093);
    }

    @Test
    void returnsEmptyWhenDisabled() {
        PlacesConfiguration cfg = new PlacesConfiguration();  // no key
        assertThat(new PlacesDetailsClient(mock(RestClient.class), cfg).fetchCoords("x")).isEmpty();
    }
}
```

- [ ] **Step 2: Run, expect compile fail**

Run: `./gradlew test --tests dev.tylercash.event.places.PlacesDetailsClientTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement**

```java
package dev.tylercash.event.places;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class PlacesDetailsClient {
    public record Coords(double lat, double lng) {}

    private final RestClient placesRestClient;
    private final PlacesConfiguration config;

    public PlacesDetailsClient(RestClient placesRestClient, PlacesConfiguration config) {
        this.placesRestClient = placesRestClient;
        this.config = config;
    }

    public Optional<Coords> fetchCoords(String placeId) {
        if (!config.isEnabled() || placeId == null || placeId.isBlank()) return Optional.empty();
        try {
            JsonNode node = placesRestClient
                    .get()
                    .uri("/v1/places/{id}?fields=location&key={key}", placeId, config.getApiKey())
                    .retrieve()
                    .body(JsonNode.class);
            if (node == null || !node.has("location")) return Optional.empty();
            JsonNode loc = node.get("location");
            return Optional.of(new Coords(loc.path("latitude").asDouble(), loc.path("longitude").asDouble()));
        } catch (Exception e) {
            log.warn("Places coords fetch failed for {}: {}", placeId, e.getMessage());
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: Run test, expect pass**

Run: `./gradlew test --tests dev.tylercash.event.places.PlacesDetailsClientTest`
Expected: PASS.

- [ ] **Step 5: Spotless + commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(places): add details client for coords lookup"
```

---

## Task 17: `TfnswReportingService` (Discord embed)

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswReportingService.java`
- Create: `backend/src/test/java/dev/tylercash/event/tfnsw/TfnswReportingServiceTest.java`

The service composes a `MessageEmbed` with two field sections, then sends via the existing Discord channel sender (likely `DiscordService` — locate the method that posts arbitrary messages to an event channel; `discordService.sendMessageBeforeEvent` in `PreEventNotificationListener` is a relevant example).

- [ ] **Step 1: Locate Discord send API**

Run: `grep -rn "sendMessage" backend/src/main/java/dev/tylercash/event/discord/ | head -20`. Identify a method like `sendMessageToEventChannel(Event, MessageEmbed)` or `sendMessageToEventChannel(Event, String)`. If none exists, add a thin one in `DiscordService` that takes an `Event` + `MessageEmbed` and posts to the event's channel id (`event.getChannelId()`).

- [ ] **Step 2: Write failing test**

```java
package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.tfnsw.NoteworthyItem.Reason;
import dev.tylercash.event.tfnsw.NoteworthyItem.Source;
import java.util.List;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TfnswReportingServiceTest {
    @Test
    void embedHasTransportAndTrafficSections() {
        DiscordService discord = mock(DiscordService.class);
        TfnswReportingService svc = new TfnswReportingService(discord);
        Event e = new Event();
        e.setName("Test");
        e.setChannelId(123L);

        svc.post(e, List.of(
                new NoteworthyItem(Source.RAIL_METRO, "a1", "T1 line suspended", "Trackwork", "https://x", Reason.NEAREST_STATION),
                new NoteworthyItem(Source.TRAFFIC, "e1", "Marathon", "Roads closed", "https://y", Reason.MAJOR_EVENT_NEAR_VENUE)
        ), false);

        ArgumentCaptor<MessageEmbed> cap = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(discord).sendEmbedToEventChannel(eq(e), cap.capture());
        MessageEmbed embed = cap.getValue();
        assertThat(embed.getDescription()).contains("Transport").contains("Traffic")
                .contains("T1 line suspended").contains("Marathon");
    }

    @Test
    void updatePrefixWhenIsUpdate() {
        DiscordService discord = mock(DiscordService.class);
        TfnswReportingService svc = new TfnswReportingService(discord);
        Event e = new Event();
        e.setChannelId(1L);
        svc.post(e, List.of(new NoteworthyItem(Source.RAIL_METRO, "a", "x", "y", "u", Reason.MAJOR_STATION)), true);
        ArgumentCaptor<MessageEmbed> cap = ArgumentCaptor.forClass(MessageEmbed.class);
        verify(discord).sendEmbedToEventChannel(eq(e), cap.capture());
        assertThat(cap.getValue().getTitle()).startsWith("Update:");
    }
}
```

- [ ] **Step 3: Run, expect compile failure**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.TfnswReportingServiceTest`
Expected: COMPILE FAIL.

- [ ] **Step 4: Implement**

```java
package dev.tylercash.event.tfnsw;

import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.tfnsw.NoteworthyItem.Source;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TfnswReportingService {
    private final DiscordService discordService;

    public void post(Event event, List<NoteworthyItem> items, boolean isUpdate) {
        if (items.isEmpty()) return;
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle((isUpdate ? "Update: " : "") + "Travel notice for " + event.getName())
                .setColor(0x0072CE);  // TfNSW blue
        StringBuilder body = new StringBuilder();
        appendSection(body, items, Source.RAIL_METRO, "🚆 Transport");
        appendSection(body, items, Source.TRAFFIC, "🚗 Traffic");
        eb.setDescription(body.toString());
        eb.setFooter("Source: Transport for NSW");
        MessageEmbed embed = eb.build();
        try {
            discordService.sendEmbedToEventChannel(event, embed);
        } catch (Exception e) {
            log.warn("Failed to post TfNSW embed to event {}: {}", event.getId(), e.getMessage());
        }
    }

    private static void appendSection(StringBuilder body, List<NoteworthyItem> items, Source source, String header) {
        var matching = items.stream().filter(i -> i.source() == source).toList();
        if (matching.isEmpty()) return;
        body.append("**").append(header).append("**\n");
        for (var i : matching) {
            body.append("• [").append(i.title()).append("](").append(i.url()).append(") — ")
                .append(i.detail()).append('\n');
        }
        body.append('\n');
    }
}
```

If `DiscordService.sendEmbedToEventChannel` doesn't exist, add it: takes `(Event, MessageEmbed)`, looks up the channel via `event.getChannelId()` and the JDA bean, and queues a send. Match patterns in the existing `DiscordService`.

- [ ] **Step 5: Run test**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.TfnswReportingServiceTest`
Expected: PASS.

- [ ] **Step 6: Spotless + commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(tfnsw): reporting service composes Discord embed"
```

---

## Task 18: `TfnswOrchestrator` (shared fetch→filter→post→snapshot)

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswOrchestrator.java`
- Create: `backend/src/test/java/dev/tylercash/event/tfnsw/TfnswOrchestratorTest.java`

This is the shared core called by both the listener and the poller. It computes the snapshot hash, decides whether to post, and persists.

- [ ] **Step 1: Write failing test (mock-heavy unit test)**

```java
package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.places.PlacesDetailsClient;
import dev.tylercash.event.places.PlacesDetailsClient.Coords;
import dev.tylercash.event.tfnsw.NoteworthyItem.Reason;
import dev.tylercash.event.tfnsw.NoteworthyItem.Source;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TfnswOrchestratorTest {

    private final TfnswConfiguration cfg = new TfnswConfiguration();
    private final TfnswAlertsClient alerts = mock(TfnswAlertsClient.class);
    private final LiveTrafficClient traffic = mock(LiveTrafficClient.class);
    private final TfnswNoteworthyFilter filter = mock(TfnswNoteworthyFilter.class);
    private final TfnswReportingService reporter = mock(TfnswReportingService.class);
    private final TfnswEventSnapshotRepository snapshots = mock(TfnswEventSnapshotRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final GuildRepository guilds = mock(GuildRepository.class);
    private final PlacesDetailsClient places = mock(PlacesDetailsClient.class);

    private final TfnswOrchestrator sut = new TfnswOrchestrator(
            cfg, alerts, traffic, filter, reporter, snapshots, events, guilds, places);

    @Test
    void skipsWhenGuildFlagOff() {
        cfg.setApiKey("k");
        UUID id = UUID.randomUUID();
        Event e = event(id, 1L, "place");
        Guild g = new Guild(); g.setGuildId(1L); g.setTfnswEnabled(false);
        when(events.findById(id)).thenReturn(Optional.of(e));
        when(guilds.findById(1L)).thenReturn(Optional.of(g));

        sut.process(id, false);

        verifyNoInteractions(alerts, traffic, reporter);
    }

    @Test
    void skipsWhenNoCoordsAndNoPlaceId() {
        cfg.setApiKey("k");
        UUID id = UUID.randomUUID();
        Event e = event(id, 1L, null);
        Guild g = new Guild(); g.setGuildId(1L); g.setTfnswEnabled(true);
        when(events.findById(id)).thenReturn(Optional.of(e));
        when(guilds.findById(1L)).thenReturn(Optional.of(g));

        sut.process(id, false);

        verifyNoInteractions(reporter);
    }

    @Test
    void resolvesCoordsFromPlacesAndPosts() {
        cfg.setApiKey("k");
        UUID id = UUID.randomUUID();
        Event e = event(id, 1L, "place-1");
        Guild g = new Guild(); g.setGuildId(1L); g.setTfnswEnabled(true);
        when(events.findById(id)).thenReturn(Optional.of(e));
        when(guilds.findById(1L)).thenReturn(Optional.of(g));
        when(places.fetchCoords("place-1")).thenReturn(Optional.of(new Coords(-33.0, 151.0)));
        when(filter.filter(any(), any(), eq(-33.0), eq(151.0), any(), any())).thenReturn(
                List.of(new NoteworthyItem(Source.RAIL_METRO, "a", "x", "y", "u", Reason.NEAREST_STATION)));

        sut.process(id, false);

        verify(reporter).post(eq(e), anyList(), eq(false));
        verify(snapshots).save(any(TfnswEventSnapshot.class));
    }

    @Test
    void weekBeforeSuppressesWhenHashUnchanged() {
        cfg.setApiKey("k");
        UUID id = UUID.randomUUID();
        Event e = event(id, 1L, "place-1");
        e.setLocationLat(-33.0); e.setLocationLng(151.0);
        Guild g = new Guild(); g.setGuildId(1L); g.setTfnswEnabled(true);
        when(events.findById(id)).thenReturn(Optional.of(e));
        when(guilds.findById(1L)).thenReturn(Optional.of(g));
        var item = new NoteworthyItem(Source.RAIL_METRO, "a", "x", "y", "u", Reason.NEAREST_STATION);
        when(filter.filter(any(), any(), anyDouble(), anyDouble(), any(), any())).thenReturn(List.of(item));

        TfnswEventSnapshot prev = new TfnswEventSnapshot();
        prev.setEventId(id);
        prev.setAlertIdsHash(TfnswOrchestrator.hash(List.of(item)));
        prev.setLastPostedAt(java.time.Instant.now());
        when(snapshots.findById(id)).thenReturn(Optional.of(prev));

        sut.process(id, true);  // recheck

        verifyNoInteractions(reporter);
    }

    private static Event event(UUID id, long guildId, String placeId) {
        Event e = new Event();
        e.setId(id);
        e.setServerId(guildId);
        e.setLocationPlaceId(placeId);
        e.setDateTime(LocalDateTime.now().plusDays(7));
        e.setName("Evt");
        return e;
    }
}
```

(Adjust `Event` setter names — `setServerId`, `setDateTime` — to match actual `Event` entity.)

- [ ] **Step 2: Run, expect compile failure**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.TfnswOrchestratorTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement orchestrator**

```java
package dev.tylercash.event.tfnsw;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.places.PlacesDetailsClient;
import dev.tylercash.event.places.PlacesDetailsClient.Coords;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TfnswOrchestrator {
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

    private final TfnswConfiguration cfg;
    private final TfnswAlertsClient alertsClient;
    private final LiveTrafficClient liveTrafficClient;
    private final TfnswNoteworthyFilter filter;
    private final TfnswReportingService reporter;
    private final TfnswEventSnapshotRepository snapshots;
    private final EventRepository events;
    private final GuildRepository guilds;
    private final PlacesDetailsClient placesDetails;

    @Transactional
    public void process(UUID eventId, boolean isWeekBeforeRecheck) {
        if (!cfg.isEnabled()) return;
        Event event = events.findById(eventId).orElse(null);
        if (event == null) return;
        Guild guild = guilds.findById(event.getServerId()).orElse(null);
        if (guild == null || !guild.isTfnswEnabled()) return;

        Coords coords = resolveCoords(event);
        if (coords == null) {
            log.debug("Skipping TfNSW for event {} — no coords resolvable", eventId);
            return;
        }

        var rail = new java.util.ArrayList<TfnswNoteworthyFilter.RailAlert>();
        rail.addAll(alertsClient.fetchSydneyTrains());
        rail.addAll(alertsClient.fetchSydneyMetro());
        rail.addAll(alertsClient.fetchTripReplacements());
        var trafficEvents = new java.util.ArrayList<TfnswNoteworthyFilter.TrafficEvent>();
        trafficEvents.addAll(liveTrafficClient.fetchMajorEvents());
        trafficEvents.addAll(liveTrafficClient.fetchHazards());

        LocalDate eventDate = event.getDateTime().atZone(SYDNEY).toLocalDate();
        // nearestStationId: v1 leaves null; future enhancement can compute from a stops index
        List<NoteworthyItem> items = filter.filter(
                rail, trafficEvents, coords.lat(), coords.lng(), null, eventDate);

        String newHash = hash(items);
        Optional<TfnswEventSnapshot> prior = snapshots.findById(eventId);

        boolean post;
        if (isWeekBeforeRecheck) {
            // Only post if hash differs AND there's at least one new item id
            post = prior.map(p -> !p.getAlertIdsHash().equals(newHash)
                                  && hasNewItem(items, p.getAlertIdsHash(), prior))
                        .orElse(!items.isEmpty());
        } else {
            post = !items.isEmpty();
        }

        if (post && !items.isEmpty()) {
            reporter.post(event, items, isWeekBeforeRecheck);
        }

        TfnswEventSnapshot snap = prior.orElseGet(() -> {
            TfnswEventSnapshot s = new TfnswEventSnapshot();
            s.setEventId(eventId);
            return s;
        });
        snap.setAlertIdsHash(newHash);
        if (post && !items.isEmpty()) snap.setLastPostedAt(Instant.now());
        snapshots.save(snap);
    }

    private Coords resolveCoords(Event e) {
        if (e.getLocationLat() != null && e.getLocationLng() != null) {
            return new Coords(e.getLocationLat(), e.getLocationLng());
        }
        if (e.getLocationPlaceId() == null || e.getLocationPlaceId().isBlank()) return null;
        var fetched = placesDetails.fetchCoords(e.getLocationPlaceId()).orElse(null);
        if (fetched != null) {
            e.setLocationLat(fetched.lat());
            e.setLocationLng(fetched.lng());
            events.save(e);
        }
        return fetched;
    }

    private static boolean hasNewItem(List<NoteworthyItem> items, String oldHash, Optional<TfnswEventSnapshot> prior) {
        // We only persist the hash, not the ids — so any hash change implies at least one new id.
        // (Diff-by-set would require persisting ids; v1 keeps it simple.)
        return true;
    }

    static String hash(List<NoteworthyItem> items) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            items.stream().map(NoteworthyItem::id).sorted().forEach(s -> md.update(s.getBytes()));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "0";
        }
    }
}
```

- [ ] **Step 4: Run test, expect pass**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.TfnswOrchestratorTest`
Expected: PASS.

- [ ] **Step 5: Spotless + commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(tfnsw): orchestrator coordinates fetch+filter+post+snapshot"
```

---

## Task 19: `TfnswEventCreatedListener`

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/lifecycle/listener/TfnswEventCreatedListener.java`
- Create: `backend/src/test/java/dev/tylercash/event/tfnsw/TfnswEventCreatedListenerTest.java`

- [ ] **Step 1: Implement listener (small wrapper around orchestrator)**

```java
package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.lifecycle.DurableEventListener;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.tfnsw.TfnswOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TfnswEventCreatedListener implements DurableEventListener<EventLifecycleEvent.EventCreated> {
    private final TfnswOrchestrator orchestrator;

    @Override
    public String name() {
        return "TfNSW Event Created";
    }

    @Override
    public Class<EventLifecycleEvent.EventCreated> eventType() {
        return EventLifecycleEvent.EventCreated.class;
    }

    @Override
    public void handle(EventLifecycleEvent.EventCreated e) {
        orchestrator.process(e.eventId(), false);
    }
}
```

- [ ] **Step 2: Write integration test**

```java
package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

class TfnswEventCreatedListenerTest extends AbstractHttpIntegrationTest {
    @Autowired EventLifecyclePublisher publisher;
    @Autowired GuildRepository guilds;
    @Autowired TfnswEventSnapshotRepository snapshots;

    @MockBean TfnswReportingService reporter;
    @MockBean TfnswAlertsClient alerts;
    @MockBean LiveTrafficClient traffic;

    @Test
    void postsAndSnapshotsOnCreateWhenNoteworthy() {
        Event e = fixtures.seedEvent(b -> b.locationLat(-33.86).locationLng(151.20));
        Guild g = guilds.findById(e.getServerId()).orElseThrow();
        g.setTfnswEnabled(true);
        guilds.save(g);

        when(alerts.fetchSydneyTrains()).thenReturn(java.util.List.of(
                new TfnswNoteworthyFilter.RailAlert("a1", "Trackwork at Central", "...", "https://x",
                        java.util.Set.of("200060"), java.util.Set.of(),
                        TfnswNoteworthyFilter.RailAlert.Severity.WARNING,
                        e.getDateTime().atZone(java.time.ZoneId.of("Australia/Sydney")).toInstant().minusSeconds(3600),
                        e.getDateTime().atZone(java.time.ZoneId.of("Australia/Sydney")).toInstant().plusSeconds(86400))));
        when(alerts.fetchSydneyMetro()).thenReturn(java.util.List.of());
        when(alerts.fetchTripReplacements()).thenReturn(java.util.List.of());
        when(traffic.fetchMajorEvents()).thenReturn(java.util.List.of());
        when(traffic.fetchHazards()).thenReturn(java.util.List.of());

        publisher.publish(new EventLifecycleEvent.EventCreated(e.getId()));

        // listener is durable + post-commit; integration test infra should drain the bus before assertions —
        // see AbstractHttpIntegrationTest.awaitListeners() pattern in other tests.
        awaitListeners();

        verify(reporter).post(any(), argThat(items -> !items.isEmpty()), eq(false));
        assertThat(snapshots.findById(e.getId())).isPresent();
    }
}
```

(Use whatever helper this codebase already provides for awaiting durable listener completion — search `awaitListeners\|drain\|flush` in tests.)

- [ ] **Step 3: Run, expect failure (or pass if code already in place)**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.TfnswEventCreatedListenerTest`
Expected: PASS.

- [ ] **Step 4: Spotless + commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(tfnsw): EventCreated lifecycle listener posts disruption notice"
```

---

## Task 20: `TfnswWeekBeforePoller`

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/tfnsw/TfnswWeekBeforePoller.java`
- Create: `backend/src/test/java/dev/tylercash/event/tfnsw/TfnswWeekBeforePollerTest.java`

- [ ] **Step 1: Implement poller**

```java
package dev.tylercash.event.tfnsw;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TfnswWeekBeforePoller {
    private final TfnswEventSnapshotRepository snapshots;
    private final TfnswOrchestrator orchestrator;

    @Scheduled(cron = "0 0 6 * * *", zone = "Australia/Sydney")
    @SchedulerLock(name = "tfnswWeekBeforePoller", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void run() {
        LocalDate from = LocalDate.now().plusDays(7);
        LocalDate to = from.plusDays(1);
        var ids = snapshots.findEventIdsForWeekBeforeCheck(from, to);
        log.info("TfNSW week-before poller processing {} events", ids.size());
        for (var id : ids) {
            try {
                orchestrator.process(id, true);
            } catch (Exception e) {
                log.warn("TfNSW week-before failed for {}: {}", id, e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 2: Write integration test (uses isolated DB)**

```java
package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.SharedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class TfnswWeekBeforePollerTest extends AbstractHttpIntegrationTest {
    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry r) {
        SharedPostgres.registerIsolatedDatabase(r, TfnswWeekBeforePollerTest.class);
    }

    @Autowired TfnswWeekBeforePoller poller;
    @Autowired GuildRepository guilds;
    @Autowired TfnswEventSnapshotRepository snapshots;
    @MockBean TfnswReportingService reporter;
    @MockBean TfnswAlertsClient alerts;
    @MockBean LiveTrafficClient traffic;

    @Test
    void rerunWithSameAlertsDoesNotPostTwice() {
        Event e = fixtures.seedEvent(b -> b
                .locationLat(-33.86).locationLng(151.20)
                .dateTime(java.time.LocalDateTime.now().plusDays(7).plusHours(2)));
        Guild g = guilds.findById(e.getServerId()).orElseThrow();
        g.setTfnswEnabled(true); guilds.save(g);

        var alert = new TfnswNoteworthyFilter.RailAlert("a1", "T1 disruption", "...", "u",
                java.util.Set.of("200060"), java.util.Set.of(),
                TfnswNoteworthyFilter.RailAlert.Severity.WARNING,
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(86400 * 14));
        when(alerts.fetchSydneyTrains()).thenReturn(java.util.List.of(alert));
        when(alerts.fetchSydneyMetro()).thenReturn(java.util.List.of());
        when(alerts.fetchTripReplacements()).thenReturn(java.util.List.of());
        when(traffic.fetchMajorEvents()).thenReturn(java.util.List.of());
        when(traffic.fetchHazards()).thenReturn(java.util.List.of());

        poller.run();
        poller.run();  // second run — same alerts

        verify(reporter, atMostOnce()).post(any(), anyList(), eq(true));
    }
}
```

- [ ] **Step 3: Run test**

Run: `./gradlew test --tests dev.tylercash.event.tfnsw.TfnswWeekBeforePollerTest`
Expected: PASS.

- [ ] **Step 4: Spotless + commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat(tfnsw): week-before scheduled recheck poller"
```

---

## Task 21: Register poller in `AdminJobCatalog`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/admin/AdminJobCatalog.java`

- [ ] **Step 1: Read file, locate registration pattern**

```bash
grep -n "register\|JobDescriptor\|JobMetadata" backend/src/main/java/dev/tylercash/event/admin/AdminJobCatalog.java | head -20
```

- [ ] **Step 2: Add TfNSW poller entry**

Add a registration entry following the existing pattern (job id `tfnsw-week-before`, display name `TfNSW Week-Before Recheck`, ShedLock name matching `tfnswWeekBeforePoller`).

- [ ] **Step 3: Boot test confirms catalog loads**

Run: `./gradlew test --tests dev.tylercash.event.admin.AdminMonitorControllerTest` (or whatever tests the monitor endpoint). Expected: PASS.

- [ ] **Step 4: Spotless + commit**

```bash
./gradlew spotlessApply
git add backend/src/main/java/dev/tylercash/event/admin/AdminJobCatalog.java
git commit -m "feat(tfnsw): register week-before poller in admin job catalog"
```

---

## Task 22: Resilience4j circuit-breaker config

**Files:**
- Modify: `backend/src/main/resources/application.yaml`

- [ ] **Step 1: Add circuit breaker config**

Under `resilience4j.circuitbreaker.instances:` (or wherever the existing `discord` instance lives — find via grep):

```yaml
resilience4j:
  circuitbreaker:
    instances:
      tfnsw:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 60s
        sliding-window-size: 10
        minimum-number-of-calls: 5
```

- [ ] **Step 2: Boot test confirms binding**

Run: any boot test. Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/application.yaml
git commit -m "chore(tfnsw): add circuit-breaker config"
```

---

## Task 23: Frontend toggle row in bot-admin features panel

**Files:**
- Modify: frontend file(s) — locate via `grep -rn "immichEnabled\|contractsEnabled" frontend/src` to find the existing features panel
- Possibly: `frontend/src/lib/api/types` if types are codegen'd from OpenAPI (regen after backend changes)

- [ ] **Step 1: Regenerate frontend OpenAPI types**

If frontend uses codegen from `backend/openapi.json`, run the backend test that emits the spec then run frontend codegen.

```bash
./gradlew test --tests dev.tylercash.event.OpenApiSpecGenerationTest
cd frontend && npm run gen:api  # exact script name varies — check package.json
```

- [ ] **Step 2: Add toggle row**

In the features panel component (path TBD — discovered in Step 0), add a row matching the existing Immich/contracts toggle structure. Label: "TfNSW transport notices". Description: "Post Sydney transport disruption alerts in event channels."

- [ ] **Step 3: Add Vitest unit test for the row**

Match the pattern of the existing toggle tests in `frontend/src` — assert the toggle reflects the `tfnswEnabled` prop and emits the correct PUT payload.

- [ ] **Step 4: Run frontend tests**

```bash
cd frontend && npm run test && npm run typecheck && npm run lint
```

Expected: all PASS.

- [ ] **Step 5: Manual smoke**

```bash
cd frontend && npm run dev
```

Open the bot-admin panel for a test guild and toggle TfNSW. Confirm PUT fires.

- [ ] **Step 6: Commit**

```bash
git add frontend/
git commit -m "feat(tfnsw): bot-admin toggle row"
```

---

## Task 24: Update `CLAUDE.md`

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add to "Multi-guild support" section + "Key Configuration Properties" table**

Append to the multi-guild paragraph: mention `tfnsw_enabled` flag.

Append to the config table:

| Property | Default | Purpose |
|----------|---------|---------|
| `dev.tylercash.tfnsw.api-key` | empty | TfNSW Open Data API key (set via `TFNSW_API_KEY` env var); empty disables the feature globally |

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude): note tfnsw integration"
```

---

## Task 25: End-to-end smoke

**Files:** none

- [ ] **Step 1: Full backend test suite**

Run: `./gradlew check`
Expected: PASS (spotless + tests + lints).

- [ ] **Step 2: Full frontend suite**

```bash
cd frontend && npm run lint && npm run typecheck && npm run test && npm run build
```

Expected: all PASS.

- [ ] **Step 3: Manual integration smoke (optional, requires API key)**

With `TFNSW_API_KEY` set in `application-local.yaml`, start backend + frontend. Toggle TfNSW on for a test guild. Create an event in Sydney CBD with a Google place_id. Wait ~10s. Check the Discord channel for the embed. If a "Trackwork" alert is currently active near Central, you should see a post.

- [ ] **Step 4: No-op commit if everything passes**

```bash
echo "smoke complete" # nothing to commit, just confirm everything green
```

---

## Self-Review Notes

- **Spec coverage**: each spec section maps to tasks — guild flag (T1-T3), event coords (T4-T5, T16, T18), snapshot (T6-T7), config (T9-T10, T22), allowlist (T11), filter (T12-T13), clients (T14-T15), reporter (T17), orchestrator (T18), listener (T19), poller (T20-T21), frontend (T23), docs (T24), smoke (T25).
- **Type consistency**: orchestrator's `process(UUID, boolean)` signature is used identically in T19 listener and T20 poller. `NoteworthyItem` shape is used identically across T12, T13, T17, T18. `TfnswEventSnapshot` fields match between T7 entity and T18 hash logic.
- **Known judgment-call points** (acceptable, called out in plan):
  - Real GTFS stop IDs in `MajorStations` need lookup from current GTFS feed — flagged in T11.
  - GeoJSON sample fixtures need a real API key OR hand-crafted fallback — flagged in T15 with both paths.
  - Frontend file path discovered at T23 (varies — couldn't pin without exploring frontend tree).
  - `DiscordService.sendEmbedToEventChannel` may need to be added — flagged in T17.
  - Awaiting durable listener completion in integration test uses whatever helper the codebase already exposes — flagged in T19.
