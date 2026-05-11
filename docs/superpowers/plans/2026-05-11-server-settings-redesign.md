# Server Settings Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the per-guild server-settings page to match the 2026-05-10 design handoff (two-column scrolling layout), add per-guild planned/archived categories + archive retention + anyone-can-create toggle, expose live Discord roles/categories to the frontend, and add a kick-bot action.

**Architecture:** Backend changes are layered bottom-up — Liquibase migration → entity → DTO/request → controller validation → new directory + kick endpoints → auto-archive lifecycle rewiring. Frontend rebuilds `GuildSettingsForm` as composed cards over shared primitives (`ChipPicker`, `SegmentedSelector`, `CFSliderLight`, `MapPreview`) with MSW handlers and per-card tests.

**Tech Stack:** Spring Boot 3.5.8, JPA, Liquibase, JDA, JUnit 5 + Testcontainers (one Postgres per JVM via `SharedPostgres`); Next.js 15, React 19, TypeScript, Tailwind, SWR, MSW, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-05-10-server-settings-redesign-design.md`

---

## File Structure

### Backend (created)

- `backend/src/main/java/dev/tylercash/event/discord/GuildDirectoryController.java` — `GET /guild/{id}/roles`, `GET /guild/{id}/categories`.
- `backend/src/main/java/dev/tylercash/event/discord/DirectoryEntry.java` — `record DirectoryEntry(String id, String name)`.
- `backend/src/main/java/dev/tylercash/event/discord/KickGuildRequest.java` — `record KickGuildRequest(String confirmGuildName)`.
- `backend/src/test/java/dev/tylercash/event/discord/GuildDirectoryControllerHttpIntegrationTest.java`
- `backend/src/test/java/dev/tylercash/event/discord/GuildControllerKickHttpIntegrationTest.java`

### Backend (modified)

- `backend/src/main/resources/db/changelog/db.changelog-master.yaml` — append one changeset adding four columns.
- `backend/src/main/java/dev/tylercash/event/discord/Guild.java` — four new fields + defaults.
- `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsDto.java`
- `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRequest.java`
- `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsController.java` — validation + new field persistence.
- `backend/src/main/java/dev/tylercash/event/discord/GuildController.java` — `DELETE /guild/{id}`.
- `backend/src/main/java/dev/tylercash/event/discord/DiscordService.java` — per-guild category resolution.
- `backend/src/main/java/dev/tylercash/event/lifecycle/EventTickScheduler.java` — per-guild archive_days.
- `backend/src/main/java/dev/tylercash/event/lifecycle/listener/EventArchiveListener.java` — drop precise re-check; resolve archived-category from guild.

### Frontend (created)

- `frontend/src/components/ui/ChipPicker.tsx`
- `frontend/src/components/ui/SegmentedSelector.tsx`
- `frontend/src/components/ui/CFSliderLight.tsx`
- `frontend/src/components/ui/MapPreview.tsx`
- `frontend/src/components/guild/settings/RsvpEmojiCard.tsx`
- `frontend/src/components/guild/settings/CategoriesArchiveCard.tsx`
- `frontend/src/components/guild/settings/PrimaryLocationCard.tsx`
- `frontend/src/components/guild/settings/RolesPermissionsCard.tsx`
- `frontend/src/components/guild/settings/CreationThrottleCard.tsx`
- `frontend/src/components/guild/settings/DangerZoneCard.tsx`
- `frontend/src/components/guild/settings/KickBotConfirmModal.tsx`
- `frontend/src/components/guild/settings/StickySaveBar.tsx`
- `frontend/src/components/guild/settings/emojiCatalog.ts` — exported 12-glyph arrays per slot.
- `frontend/src/__tests__/components/GuildSettingsForm.kickBot.test.tsx`
- `frontend/src/__tests__/components/GuildSettingsForm.crossValidation.test.tsx`

### Frontend (modified)

- `frontend/src/components/guild/GuildSettingsForm.tsx` — full rewrite.
- `frontend/src/lib/hooks.ts` — add `useGuildRoles`, `useGuildCategories`, `kickBotFromGuild`.
- `frontend/src/mocks/handlers.ts` — handlers for the three new endpoints + four new settings fields.
- `frontend/src/mocks/fixtures.ts` — extend guild settings fixture.
- `frontend/tailwind.config.ts` — add `danger`, `rsvpGoing`, `rsvpNo`, `rsvpMaybe`.
- `frontend/src/__tests__/components/GuildSettingsForm.test.tsx` — replace tab-based assertions.
- `frontend/src/__tests__/render/GuildSettingsForm.test.tsx` — update copy.

---

## Conventions used in this plan

- Backend tests run via `./gradlew test --tests "<FQCN>"`. Always use Testcontainers; do NOT add mocks for Postgres.
- HTTP integration tests extend `AbstractHttpIntegrationTest` (see existing `GuildControllerHttpIntegrationTest`). Use `fixtures.registerMember(...)` / `fixtures.seedEvent(...)` and generate unique IDs with `TestIds.nextLong()` / `nextSnowflake()`.
- Frontend tests run via `npm run test -- <path>` from `frontend/`. Render tests use MSW handlers; component tests stub network via `fixtures.ts`.
- All commits use Conventional Commits. New persisted columns / endpoints use `feat:`; refactors use `refactor:`; pure-test commits use `test:`.

---

## Task 1: Liquibase migration adding four `guild` columns

**Files:**
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append at end)

- [ ] **Step 1: Append the changeset**

Append (preserving file indentation — two-space, no tabs):

```yaml
  - changeSet:
      id: server-settings-redesign columns
      author: tyler
      comment: >
        Adds per-guild category configuration (planned + archived), archive
        retention (7/14/30/90, default 90), and the anyone-can-create toggle
        introduced by the server-settings redesign.
      changes:
        - addColumn:
            tableName: guild
            columns:
              - column:
                  name: planned_category_id
                  type: text
              - column:
                  name: archived_category_id
                  type: text
              - column:
                  name: archive_days
                  type: integer
                  defaultValueNumeric: 90
                  constraints:
                    nullable: false
              - column:
                  name: anyone_can_create
                  type: boolean
                  defaultValueBoolean: true
                  constraints:
                    nullable: false
        - sql:
            sql: >
              ALTER TABLE guild ADD CONSTRAINT guild_archive_days_check
              CHECK (archive_days IN (7, 14, 30, 90))
```

- [ ] **Step 2: Run backend tests to confirm Liquibase boots cleanly**

Run: `cd backend && ./gradlew test --tests "dev.tylercash.event.discord.GuildSettingsControllerHttpIntegrationTest" -i`
Expected: PASS (existing tests still green; migration applies on container startup).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(db): add server-settings columns to guild table"
```

---

## Task 2: Extend `Guild` entity with the new fields + defaults

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/Guild.java`

- [ ] **Step 1: Add the four fields after `eventCreateRateLimitPerHour`**

```java
    @Column(name = "planned_category_id")
    private String plannedCategoryId;

    @Column(name = "archived_category_id")
    private String archivedCategoryId;

    @Column(name = "archive_days", nullable = false)
    private int archiveDays;

    @Column(name = "anyone_can_create", nullable = false)
    private boolean anyoneCanCreate;
```

- [ ] **Step 2: Update `Guild.withDefaults` to set defaults**

Add to the existing builder method (preserving order after `setTfnswEnabled(false)`):

```java
        g.setArchiveDays(90);
        g.setAnyoneCanCreate(true);
```

`plannedCategoryId` and `archivedCategoryId` stay null by default.

- [ ] **Step 3: Run existing controller tests**

Run: `cd backend && ./gradlew test --tests "dev.tylercash.event.discord.GuildSettingsControllerHttpIntegrationTest" -i`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/Guild.java
git commit -m "feat(guild): persist planned/archived category, archive days, anyone-can-create"
```

---

## Task 3: Extend `GuildSettingsDto` and `GuildSettingsRequest`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsDto.java`
- Modify: `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRequest.java`

- [ ] **Step 1: Add four fields to `GuildSettingsDto` record**

Add the parameters in this order after `eventCreateRateLimitPerHour`: `String plannedCategoryId, String archivedCategoryId, int archiveDays, boolean anyoneCanCreate`. Place them BEFORE `int defaultEventCreateRateLimitPerHour` so the default-limit stays the last positional parameter. Update `GuildSettingsDto.from(...)` to pass `row.getPlannedCategoryId(), row.getArchivedCategoryId(), row.getArchiveDays(), row.isAnyoneCanCreate()` in that position.

- [ ] **Step 2: Add four fields to `GuildSettingsRequest`**

The class is a record. Add at the end (all nullable so partial updates remain supported): `String plannedCategoryId, String archivedCategoryId, Integer archiveDays, Boolean anyoneCanCreate`.

- [ ] **Step 3: Compile + run controller test**

Run: `cd backend && ./gradlew test --tests "dev.tylercash.event.discord.GuildSettingsControllerHttpIntegrationTest" -i`
Expected: PASS — existing tests will deserialise the larger DTO, no breakage.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/GuildSettingsDto.java backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRequest.java
git commit -m "feat(api): expose archive + category + anyone-can-create on settings DTO"
```

---

## Task 4: Extend `GuildSettingsController` with validation

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsController.java`
- Modify: `backend/src/test/java/dev/tylercash/event/discord/GuildSettingsControllerHttpIntegrationTest.java`

- [ ] **Step 1: Write failing test — archive_days range**

In the existing test class (use the same `fixtures` / WebTestClient pattern as sibling tests), add:

```java
    @Test
    void rejectsInvalidArchiveDays() {
        long ownerSnowflake = TestIds.nextSnowflake();
        long guildId = TestIds.nextSnowflake();
        fixtures.registerOwner(ownerSnowflake, guildId);

        webClient
            .mutateWith(loggedInAs(ownerSnowflake))
            .patch().uri("/guild/{id}/settings", guildId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("archiveDays", 15))
            .exchange()
            .expectStatus().isBadRequest();
    }
```

- [ ] **Step 2: Write failing test — planned == archived**

```java
    @Test
    void rejectsPlannedEqualToArchivedCategory() {
        long ownerSnowflake = TestIds.nextSnowflake();
        long guildId = TestIds.nextSnowflake();
        fixtures.registerOwner(ownerSnowflake, guildId);
        String sameId = String.valueOf(TestIds.nextSnowflake());

        webClient
            .mutateWith(loggedInAs(ownerSnowflake))
            .patch().uri("/guild/{id}/settings", guildId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("plannedCategoryId", sameId, "archivedCategoryId", sameId))
            .exchange()
            .expectStatus().isBadRequest();
    }
```

- [ ] **Step 3: Write failing test — anyoneCanCreate=false clears rate limit**

```java
    @Test
    void clearsRateLimitWhenAnyoneCanCreateFalse() {
        long ownerSnowflake = TestIds.nextSnowflake();
        long guildId = TestIds.nextSnowflake();
        fixtures.registerOwner(ownerSnowflake, guildId);

        webClient
            .mutateWith(loggedInAs(ownerSnowflake))
            .patch().uri("/guild/{id}/settings", guildId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("anyoneCanCreate", false, "eventCreateRateLimitPerHour", 5))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.eventCreateRateLimitPerHour").doesNotExist()
            .jsonPath("$.anyoneCanCreate").isEqualTo(false);
    }
```

- [ ] **Step 4: Run tests to verify failures**

Run: `cd backend && ./gradlew test --tests "dev.tylercash.event.discord.GuildSettingsControllerHttpIntegrationTest" -i`
Expected: three new tests FAIL (400 not returned / rate limit still set).

- [ ] **Step 5: Implement validation in `GuildSettingsController#updateSettings`**

Insert immediately after the existing `requestedLimit` validation block, before `guildRepository.save(row)`:

```java
        // Validate archiveDays (defensive — DB CHECK is the authority)
        Integer requestedArchiveDays = request.archiveDays();
        if (requestedArchiveDays != null) {
            if (!Set.of(7, 14, 30, 90).contains(requestedArchiveDays)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "archiveDays must be one of {7, 14, 30, 90}");
            }
            row.setArchiveDays(requestedArchiveDays);
        }

        // Categories: null clears, both non-null must differ.
        String requestedPlanned = request.plannedCategoryId();
        String requestedArchived = request.archivedCategoryId();
        if (requestedPlanned != null
                && requestedArchived != null
                && requestedPlanned.equals(requestedArchived)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "planned and archived categories must differ");
        }
        // Cross-check against the row's current value when only one side is being updated.
        if (requestedPlanned != null
                && requestedArchived == null
                && requestedPlanned.equals(row.getArchivedCategoryId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "planned category cannot equal current archived category");
        }
        if (requestedArchived != null
                && requestedPlanned == null
                && requestedArchived.equals(row.getPlannedCategoryId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "archived category cannot equal current planned category");
        }
        row.setPlannedCategoryId(requestedPlanned);
        row.setArchivedCategoryId(requestedArchived);

        // anyoneCanCreate: when false, force-clear the rate limit (organisers bypass it).
        Boolean requestedAnyoneCanCreate = request.anyoneCanCreate();
        if (requestedAnyoneCanCreate != null) {
            row.setAnyoneCanCreate(requestedAnyoneCanCreate);
            if (!requestedAnyoneCanCreate) {
                row.setEventCreateRateLimitPerHour(null);
            }
        }
```

Also: move the existing `row.setEventCreateRateLimitPerHour(requestedLimit)` line so it runs ONLY when `requestedAnyoneCanCreate == null || requestedAnyoneCanCreate` (otherwise the force-clear above wins). The simplest approach is to gate it:

```java
        if (requestedAnyoneCanCreate == null || requestedAnyoneCanCreate) {
            row.setEventCreateRateLimitPerHour(requestedLimit);
        }
```

Replace the existing unconditional `row.setEventCreateRateLimitPerHour(requestedLimit);` with this gated version.

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests "dev.tylercash.event.discord.GuildSettingsControllerHttpIntegrationTest" -i`
Expected: ALL PASS.

- [ ] **Step 7: Add a happy-path test for the new fields**

```java
    @Test
    void persistsArchiveAndCategoryAndAnyoneCanCreate() {
        long ownerSnowflake = TestIds.nextSnowflake();
        long guildId = TestIds.nextSnowflake();
        fixtures.registerOwner(ownerSnowflake, guildId);
        String planned = String.valueOf(TestIds.nextSnowflake());
        String archived = String.valueOf(TestIds.nextSnowflake());

        webClient
            .mutateWith(loggedInAs(ownerSnowflake))
            .patch().uri("/guild/{id}/settings", guildId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "plannedCategoryId", planned,
                "archivedCategoryId", archived,
                "archiveDays", 30,
                "anyoneCanCreate", true))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.plannedCategoryId").isEqualTo(planned)
            .jsonPath("$.archivedCategoryId").isEqualTo(archived)
            .jsonPath("$.archiveDays").isEqualTo(30)
            .jsonPath("$.anyoneCanCreate").isEqualTo(true);
    }
```

Run the test, confirm PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/GuildSettingsController.java backend/src/test/java/dev/tylercash/event/discord/GuildSettingsControllerHttpIntegrationTest.java
git commit -m "feat(guild-settings): validate and persist redesign fields"
```

---

## Task 5: `DirectoryEntry` record + `GuildDirectoryController` skeleton

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/DirectoryEntry.java`
- Create: `backend/src/main/java/dev/tylercash/event/discord/GuildDirectoryController.java`
- Create: `backend/src/test/java/dev/tylercash/event/discord/GuildDirectoryControllerHttpIntegrationTest.java`

- [ ] **Step 1: Create the record**

```java
package dev.tylercash.event.discord;

public record DirectoryEntry(String id, String name) {}
```

- [ ] **Step 2: Write failing test — non-member returns 403**

Create the integration test extending `AbstractHttpIntegrationTest`. Use a mocked `JDA` from the test base (existing tests already do — copy the wiring from `GuildControllerHttpIntegrationTest`).

```java
    @Test
    void rolesEndpointDeniesNonMember() {
        long guildId = TestIds.nextSnowflake();
        long snowflake = TestIds.nextSnowflake();
        // No registerMember call — user is not a member.

        webClient
            .mutateWith(loggedInAs(snowflake))
            .get().uri("/guild/{id}/roles", guildId)
            .exchange()
            .expectStatus().isForbidden();
    }
```

- [ ] **Step 3: Write failing test — roles sorted, @everyone and managed roles excluded**

Mock JDA so `jda.getGuildById(guildId)` returns a Guild whose `getRoles()` includes: `@everyone` (managed), a managed integration role, and three normal roles named "Zebra", "Aardvark", "Mango". Use Mockito on JDA primitives — see `DiscordRoleService` tests for patterns.

```java
    @Test
    void rolesEndpointReturnsSortedNonManagedRoles() {
        long guildId = TestIds.nextSnowflake();
        long snowflake = TestIds.nextSnowflake();
        fixtures.registerMember(snowflake, guildId);
        fixtures.stubGuildRoles(guildId, List.of(
            stubRole("0", "@everyone", true),
            stubRole("100", "Zebra", false),
            stubRole("200", "Aardvark", false),
            stubRole("300", "MangoBot", true),   // managed
            stubRole("400", "Mango", false)));

        webClient
            .mutateWith(loggedInAs(snowflake))
            .get().uri("/guild/{id}/roles", guildId)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.length()").isEqualTo(3)
            .jsonPath("$[0].name").isEqualTo("Aardvark")
            .jsonPath("$[1].name").isEqualTo("Mango")
            .jsonPath("$[2].name").isEqualTo("Zebra");
    }
```

(`fixtures.stubGuildRoles` and `stubRole` will be added to the fixture helper as part of this task.)

- [ ] **Step 4: Write failing test — categories sorted**

```java
    @Test
    void categoriesEndpointReturnsSortedCategories() {
        long guildId = TestIds.nextSnowflake();
        long snowflake = TestIds.nextSnowflake();
        fixtures.registerMember(snowflake, guildId);
        fixtures.stubGuildCategories(guildId, List.of(
            stubCategory("200", "Zeta"),
            stubCategory("100", "Alpha")));

        webClient
            .mutateWith(loggedInAs(snowflake))
            .get().uri("/guild/{id}/categories", guildId)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$[0].name").isEqualTo("Alpha")
            .jsonPath("$[1].name").isEqualTo("Zeta");
    }
```

- [ ] **Step 5: Write failing test — empty list when JDA cache cold**

```java
    @Test
    void rolesEndpointReturnsEmptyWhenJdaCacheCold() {
        long guildId = TestIds.nextSnowflake();
        long snowflake = TestIds.nextSnowflake();
        fixtures.registerMember(snowflake, guildId);
        fixtures.stubMissingJdaGuild(guildId);

        webClient
            .mutateWith(loggedInAs(snowflake))
            .get().uri("/guild/{id}/roles", guildId)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.length()").isEqualTo(0);
    }
```

- [ ] **Step 6: Extend fixture helper**

In `backend/src/test/java/.../test/Fixtures.java` (or whatever the project's helper is — match the location used by `GuildControllerHttpIntegrationTest`), add:

```java
    public void stubGuildRoles(long guildId, List<Role> roles) {
        Guild g = ensureGuildStub(guildId);
        when(g.getRoles()).thenReturn(roles);
    }

    public void stubGuildCategories(long guildId, List<Category> categories) {
        Guild g = ensureGuildStub(guildId);
        when(g.getCategories()).thenReturn(categories);
    }

    public void stubMissingJdaGuild(long guildId) {
        when(jda.getGuildById(guildId)).thenReturn(null);
    }

    private Guild ensureGuildStub(long guildId) {
        Guild g = jda.getGuildById(guildId);
        if (g == null) {
            g = mock(Guild.class);
            when(jda.getGuildById(guildId)).thenReturn(g);
        }
        return g;
    }
```

Add static helpers in the test class itself:

```java
    private static Role stubRole(String id, String name, boolean managed) {
        Role r = mock(Role.class);
        when(r.getId()).thenReturn(id);
        when(r.getName()).thenReturn(name);
        when(r.isManaged()).thenReturn(managed);
        return r;
    }

    private static Category stubCategory(String id, String name) {
        Category c = mock(Category.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        return c;
    }
```

- [ ] **Step 7: Run tests to verify failures**

Run: `cd backend && ./gradlew test --tests "dev.tylercash.event.discord.GuildDirectoryControllerHttpIntegrationTest"`
Expected: FAIL (no controller yet, 404 on routes).

- [ ] **Step 8: Implement `GuildDirectoryController`**

```java
package dev.tylercash.event.discord;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/guild/{guildId}")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Guild", description = "Discord guild info")
public class GuildDirectoryController {

    private final GuildMembershipService guildMembershipService;
    private final JDA jda;

    @GetMapping("/roles")
    public List<DirectoryEntry> roles(
            @PathVariable String guildId,
            @AuthenticationPrincipal OAuth2User principal) {
        long guildIdLong = authoriseAndResolve(guildId, principal);
        Guild jdaGuild = jda.getGuildById(guildIdLong);
        if (jdaGuild == null) {
            log.debug("JDA cache miss for guild {} while listing roles", guildIdLong);
            return List.of();
        }
        return jdaGuild.getRoles().stream()
                .filter(r -> !r.isManaged())
                .filter(r -> !r.getName().equals("@everyone"))
                .map(r -> new DirectoryEntry(r.getId(), r.getName()))
                .sorted(Comparator.comparing(DirectoryEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @GetMapping("/categories")
    public List<DirectoryEntry> categories(
            @PathVariable String guildId,
            @AuthenticationPrincipal OAuth2User principal) {
        long guildIdLong = authoriseAndResolve(guildId, principal);
        Guild jdaGuild = jda.getGuildById(guildIdLong);
        if (jdaGuild == null) {
            log.debug("JDA cache miss for guild {} while listing categories", guildIdLong);
            return List.of();
        }
        return jdaGuild.getCategories().stream()
                .map(c -> new DirectoryEntry(c.getId(), c.getName()))
                .sorted(Comparator.comparing(DirectoryEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private long authoriseAndResolve(String guildId, OAuth2User principal) {
        String snowflake = principal.getAttribute("id");
        long guildIdLong = Long.parseLong(guildId);
        guildMembershipService.assertMember(snowflake, guildIdLong);
        return guildIdLong;
    }
}
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests "dev.tylercash.event.discord.GuildDirectoryControllerHttpIntegrationTest"`
Expected: ALL PASS.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/DirectoryEntry.java backend/src/main/java/dev/tylercash/event/discord/GuildDirectoryController.java backend/src/test/java/dev/tylercash/event/discord/GuildDirectoryControllerHttpIntegrationTest.java backend/src/test/java/dev/tylercash/event/test/Fixtures.java
git commit -m "feat(guild): list roles and categories from JDA"
```

(Adjust the Fixtures.java path if the file lives elsewhere — locate via `grep -r "registerMember" backend/src/test/java`.)

---

## Task 6: `DELETE /guild/{id}` kick-bot endpoint

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/KickGuildRequest.java`
- Modify: `backend/src/main/java/dev/tylercash/event/discord/GuildController.java`
- Create: `backend/src/test/java/dev/tylercash/event/discord/GuildControllerKickHttpIntegrationTest.java`

- [ ] **Step 1: Create the request record**

```java
package dev.tylercash.event.discord;

public record KickGuildRequest(String confirmGuildName) {}
```

- [ ] **Step 2: Write failing test — non-owner denied**

Use the existing `GuildControllerHttpIntegrationTest` style. Reuse `fixtures.registerMember` for membership and the existing owner-stub helper (or add one if missing).

```java
    @Test
    void nonOwnerCannotKickBot() {
        long ownerSnowflake = TestIds.nextSnowflake();
        long memberSnowflake = TestIds.nextSnowflake();
        long guildId = TestIds.nextSnowflake();
        fixtures.registerOwner(ownerSnowflake, guildId);
        fixtures.registerMember(memberSnowflake, guildId);
        fixtures.stubGuildName(guildId, "Porch Pigeons");

        webClient
            .mutateWith(loggedInAs(memberSnowflake))
            .method(HttpMethod.DELETE).uri("/guild/{id}", guildId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("confirmGuildName", "Porch Pigeons"))
            .exchange()
            .expectStatus().isForbidden();
    }
```

- [ ] **Step 3: Write failing test — name mismatch yields 400**

```java
    @Test
    void mismatchedGuildNameYields400() {
        long ownerSnowflake = TestIds.nextSnowflake();
        long guildId = TestIds.nextSnowflake();
        fixtures.registerOwner(ownerSnowflake, guildId);
        fixtures.stubGuildName(guildId, "Porch Pigeons");

        webClient
            .mutateWith(loggedInAs(ownerSnowflake))
            .method(HttpMethod.DELETE).uri("/guild/{id}", guildId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("confirmGuildName", "wrong name"))
            .exchange()
            .expectStatus().isBadRequest();
    }
```

- [ ] **Step 4: Write failing test — success calls JDA `leave()` and returns 204**

```java
    @Test
    void ownerWithMatchingNameTriggersLeave() {
        long ownerSnowflake = TestIds.nextSnowflake();
        long guildId = TestIds.nextSnowflake();
        fixtures.registerOwner(ownerSnowflake, guildId);
        RestAction<Void> leaveAction = mock(RestAction.class);
        net.dv8tion.jda.api.entities.Guild jdaGuild = fixtures.stubGuildName(guildId, "Porch Pigeons");
        when(jdaGuild.leave()).thenReturn(leaveAction);

        webClient
            .mutateWith(loggedInAs(ownerSnowflake))
            .method(HttpMethod.DELETE).uri("/guild/{id}", guildId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("confirmGuildName", "  porch pigeons  "))   // trim + case-insensitive
            .exchange()
            .expectStatus().isNoContent();

        verify(leaveAction).queue();
    }
```

- [ ] **Step 5: Extend fixtures**

Add to the test fixtures helper:

```java
    public net.dv8tion.jda.api.entities.Guild stubGuildName(long guildId, String name) {
        net.dv8tion.jda.api.entities.Guild g = ensureGuildStub(guildId);
        when(g.getName()).thenReturn(name);
        return g;
    }
```

`registerOwner` should already imply both `registerMember` and `discordAuthService.isGuildOwner(guildId, snowflake) == true`. If it doesn't, add an `registerOwner` helper that wraps both.

- [ ] **Step 6: Run tests to verify failures**

Run: `cd backend && ./gradlew test --tests "dev.tylercash.event.discord.GuildControllerKickHttpIntegrationTest"`
Expected: FAIL with 404 (endpoint doesn't exist).

- [ ] **Step 7: Add `DELETE` handler to `GuildController`**

```java
    @DeleteMapping("/{guildId}")
    public ResponseEntity<Void> kick(
            @PathVariable String guildId,
            @RequestBody KickGuildRequest body,
            @AuthenticationPrincipal OAuth2User principal) {
        String snowflake = principal.getAttribute("id");
        long guildIdLong = Long.parseLong(guildId);
        long userId = Long.parseLong(snowflake);
        guildMembershipService.assertMember(snowflake, guildIdLong);
        if (!discordAuthService.isGuildOwner(guildIdLong, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the guild owner can remove peepbot");
        }
        net.dv8tion.jda.api.entities.Guild jdaGuild = jda.getGuildById(guildIdLong);
        if (jdaGuild == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Guild not found");
        }
        String expected = jdaGuild.getName().trim();
        String supplied = body == null || body.confirmGuildName() == null
                ? ""
                : body.confirmGuildName().trim();
        if (!expected.equalsIgnoreCase(supplied)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "guild name confirmation does not match");
        }
        log.info(
                "AUDIT user {} kicking peepbot from guild {} ({})",
                snowflake,
                guildIdLong,
                expected);
        jdaGuild.leave().queue();
        return ResponseEntity.noContent().build();
    }
```

Add new constructor-injected fields if missing: `GuildMembershipService guildMembershipService`, `DiscordAuthService discordAuthService`, `JDA jda`. Add `@Slf4j` and the relevant imports (`HttpStatus`, `ResponseStatusException`, `ResponseEntity`, `DeleteMapping`, `RequestBody`).

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests "dev.tylercash.event.discord.GuildControllerKickHttpIntegrationTest"`
Expected: ALL PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/KickGuildRequest.java backend/src/main/java/dev/tylercash/event/discord/GuildController.java backend/src/test/java/dev/tylercash/event/discord/GuildControllerKickHttpIntegrationTest.java backend/src/test/java/dev/tylercash/event/test/Fixtures.java
git commit -m "feat(guild): owner-only DELETE /guild/{id} to kick peepbot"
```

---

## Task 7: Per-guild category resolution in `DiscordService`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/DiscordService.java`

- [ ] **Step 1: Add `GuildRepository` to constructor injection**

`DiscordService` may or may not already inject `GuildRepository` — check first (`grep -n "GuildRepository" backend/src/main/java/dev/tylercash/event/discord/DiscordService.java`). If absent, add the field, constructor arg, and Spring will wire it.

- [ ] **Step 2: Add the resolution helpers**

Add two private methods near the top of the class:

```java
    private Category resolvePlannedCategory(long guildId) {
        return guildRepository.findById(guildId)
                .map(Guild::getPlannedCategoryId)
                .map(id -> jda.getGuildById(guildId) == null ? null : jda.getGuildById(guildId).getCategoryById(id))
                .orElseGet(() -> discordChannelService.getCategoryByName(guildId, EVENT_CATEGORY));
    }

    private Category resolveArchivedCategory(long guildId) {
        return guildRepository.findById(guildId)
                .map(Guild::getArchivedCategoryId)
                .map(id -> jda.getGuildById(guildId) == null ? null : jda.getGuildById(guildId).getCategoryById(id))
                .orElseGet(() -> discordChannelService.getCategoryByName(guildId, EVENT_ARCHIVE_CATEGORY));
    }
```

Note: `Optional.map` returns empty if the mapper returns null, so the fall-back to named lookup runs when the row exists but `*_category_id` is null, AND when JDA can't find the configured category id any more (stale snowflake). This is the desired behaviour — never crash on stale config.

- [ ] **Step 3: Route the existing call sites through the helpers**

Replace these calls (line numbers from `git grep`, may drift):

- `discordChannelService.getCategoryByName(event.getServerId(), EVENT_CATEGORY)` (two occurrences around L64, L294) → `resolvePlannedCategory(event.getServerId())`.
- `discordChannelService.getCategoryByName(serverId, EVENT_CATEGORY)` (around L73) → `resolvePlannedCategory(serverId)`.
- `discordChannelService.getCategoryByName(row.getGuildId(), EVENT_ARCHIVE_CATEGORY)` (around L186) → `resolveArchivedCategory(row.getGuildId())`.
- `discordChannelService.getCategoryByName(guildId, EVENT_ARCHIVE_CATEGORY)` (around L247) → `resolveArchivedCategory(guildId)`.
- `discordChannelService.getCategoryByName(event.getServerId(), EVENT_ARCHIVE_CATEGORY)` (around L327) → `resolveArchivedCategory(event.getServerId())`.

Verify with `grep -n "EVENT_CATEGORY\|EVENT_ARCHIVE_CATEGORY" backend/src/main/java/dev/tylercash/event/discord/DiscordService.java` — only the constant declarations should remain.

- [ ] **Step 4: Run the discord-service tests**

Run: `cd backend && ./gradlew test --tests "dev.tylercash.event.discord.*"`
Expected: PASS. Any test that pre-seeds an `EVENT_CATEGORY` named category should still pass because the helper falls back to name when `plannedCategoryId` is null.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/DiscordService.java
git commit -m "refactor(discord): resolve event categories per-guild with name fallback"
```

---

## Task 8: Per-guild `archive_days` in `EventTickScheduler`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/lifecycle/EventTickScheduler.java`

- [ ] **Step 1: Inject `GuildRepository`**

Add as a `final` field on the class; Lombok's `@RequiredArgsConstructor` will wire it.

- [ ] **Step 2: Widen the ARCHIVAL window and gate per-event**

Replace the existing ARCHIVAL `emitTick(...)` call with:

```java
        // ARCHIVAL: candidate window is dateTime < now - 7 days (the smallest legal archive_days).
        // We then per-event check the guild's configured archive_days before publishing.
        emitArchivalTicks(now);
```

And add the method:

```java
    private void emitArchivalTicks(ZonedDateTime now) {
        ZonedDateTime from = now.minusYears(10);
        ZonedDateTime to = now.minusDays(7);
        for (Event e : events.findInDateWindow(from, to, EventState.POST_COMPLETED)) {
            int days = guildRepository.findById(e.getServerId())
                    .map(g -> g.getArchiveDays() == 0 ? 90 : g.getArchiveDays())
                    .orElse(90);
            if (now.isBefore(e.getDateTime().plusDays(days))) {
                continue;
            }
            EventTickLogId id = new EventTickLogId(e.getId(), "ARCHIVAL");
            if (tickLog.existsById(id)) continue;
            EventTickLog row = new EventTickLog();
            row.setEventId(e.getId());
            row.setTickType("ARCHIVAL");
            tickLog.save(row);
            try {
                publisher.publish(new EventLifecycleEvent.EventArchivalDue(e.getId()));
            } catch (Exception ex) {
                log.error("Failed to publish ARCHIVAL for event {}", e.getId(), ex);
            }
        }
    }
```

- [ ] **Step 3: Remove the now-stale comment block**

Delete the multi-line comment above the old ARCHIVAL call (lines 46-51 in the original — the "ArchiveOperation guard inside the listener" paragraph) since the listener no longer rejects.

- [ ] **Step 4: Write a scheduler test**

Look for an existing `EventTickSchedulerTest` (`find backend/src/test -name "EventTickSchedulerTest*"`). If present, add:

```java
    @Test
    void respectsPerGuildArchiveDays() {
        long guildId = TestIds.nextSnowflake();
        Guild g = Guild.withDefaults(guildId);
        g.setArchiveDays(30);
        guildRepository.save(g);

        UUID eventId = fixtures.seedEventInState(
                guildId, EventState.POST_COMPLETED, ZonedDateTime.now(clock).minusDays(20));
        // 20 days old, threshold 30 → must NOT emit.
        scheduler.emit();
        assertThat(publisher.published()).isEmpty();

        UUID readyId = fixtures.seedEventInState(
                guildId, EventState.POST_COMPLETED, ZonedDateTime.now(clock).minusDays(31));
        // 31 days old, threshold 30 → must emit.
        scheduler.emit();
        assertThat(publisher.published())
                .extracting("eventId")
                .containsExactly(readyId);
    }
```

(Adjust `fixtures.seedEventInState` / `publisher.published()` to match local conventions — if the test infra needs new helpers, add them.)

If no scheduler test exists, create `EventTickSchedulerTest.java` extending `AbstractHttpIntegrationTest` (or whichever JPA-aware test base sibling lifecycle tests use). Use `SharedPostgres.registerIsolatedDatabase(registry, EventTickSchedulerTest.class)` because the scheduler scans all `POST_COMPLETED` events globally (per CLAUDE.md guidance for global-scan tests).

- [ ] **Step 5: Run the test**

Run: `cd backend && ./gradlew test --tests "dev.tylercash.event.lifecycle.EventTickSchedulerTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/lifecycle/EventTickScheduler.java backend/src/test/java/dev/tylercash/event/lifecycle/EventTickSchedulerTest.java
git commit -m "feat(lifecycle): honour per-guild archive_days in the archival ticker"
```

---

## Task 9: Drop precise re-check in `EventArchiveListener`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/lifecycle/listener/EventArchiveListener.java`
- Modify: any existing test that asserted the IllegalStateException path

- [ ] **Step 1: Remove the precise re-check block**

Delete lines that compute `archiveTime` and the `if (now.isBefore(archiveTime)) throw …` block (currently lines 59-69 of `EventArchiveListener.java`). Also drop the `Clock clock` field and constructor arg if they become unused, plus the `ZonedDateTime`/`Clock` imports.

- [ ] **Step 2: Find and update tests**

Run: `grep -rln "not yet ready for archival\|EventArchive.*Clock" backend/src/test`
Update any test that exercised the early-throw → retry path: either delete the test (the behaviour no longer exists by design — note this in the commit) or convert it to assert that the listener simply runs successfully.

- [ ] **Step 3: Run archive listener tests**

Run: `cd backend && ./gradlew test --tests "dev.tylercash.event.lifecycle.listener.EventArchiveListenerTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/lifecycle/listener/EventArchiveListener.java backend/src/test/java/dev/tylercash/event/lifecycle/listener/EventArchiveListenerTest.java
git commit -m "refactor(lifecycle): trust scheduler timing in EventArchiveListener"
```

---

## Task 10: Full backend regression

- [ ] **Step 1: Run the full backend test suite**

Run: `cd backend && ./gradlew test`
Expected: ALL PASS. Fix any regressions before moving to the frontend.

- [ ] **Step 2: Run spotless**

Run: `cd backend && ./gradlew spotlessApply`
Commit any formatting fix-ups.

```bash
git add -A backend
git diff --cached --quiet || git commit -m "style: spotless apply"
```

---

## Task 11: Tailwind tokens for danger + RSVP slots

**Files:**
- Modify: `frontend/tailwind.config.ts`

- [ ] **Step 1: Add the four new color tokens**

In the `theme.extend.colors` object (alphabetical/grouped with existing entries), add:

```ts
        danger: "#DC2626",
        rsvpGoing: "#E6F4D8",
        rsvpNo: "#FCE5E8",
        rsvpMaybe: "#F5EDD2",
```

- [ ] **Step 2: Run typecheck**

Run: `cd frontend && npm run typecheck`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add frontend/tailwind.config.ts
git commit -m "feat(ui): add danger and rsvp slot color tokens"
```

---

## Task 12: `ChipPicker` primitive

**Files:**
- Create: `frontend/src/components/ui/ChipPicker.tsx`
- Create: `frontend/src/__tests__/components/ChipPicker.test.tsx`

- [ ] **Step 1: Write failing test**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { ChipPicker } from "@/components/ui/ChipPicker";

const options = [
  { id: "1", name: "Aardvark" },
  { id: "2", name: "Mango" },
  { id: "3", name: "Zebra" },
];

describe("ChipPicker", () => {
  it("renders the selected option's name", () => {
    render(<ChipPicker value="2" onChange={vi.fn()} options={options} ready label="Notification role" />);
    expect(screen.getByRole("button", { name: /mango/i })).toBeInTheDocument();
  });

  it("opens the option list on click and selects an option", () => {
    const onChange = vi.fn();
    render(<ChipPicker value="1" onChange={onChange} options={options} ready label="x" />);
    fireEvent.click(screen.getByRole("button", { name: /aardvark/i }));
    fireEvent.click(screen.getByText("Zebra"));
    expect(onChange).toHaveBeenCalledWith("3");
  });

  it("shows skeleton chips while not ready", () => {
    render(<ChipPicker value={null} onChange={vi.fn()} options={[]} ready={false} label="x" />);
    expect(screen.getByText(/syncing with discord/i)).toBeInTheDocument();
  });

  it("renders disabled options with strikethrough and ignores clicks on them", () => {
    const onChange = vi.fn();
    render(
      <ChipPicker
        value={null}
        onChange={onChange}
        options={options}
        disabledIds={["2"]}
        ready
        label="x"
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /select/i }));
    const disabled = screen.getByText("Mango");
    expect(disabled).toHaveClass("line-through");
    fireEvent.click(disabled);
    expect(onChange).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify failure**

Run: `cd frontend && npm run test -- ChipPicker`
Expected: FAIL (module not found).

- [ ] **Step 3: Implement `ChipPicker`**

```tsx
"use client";

import { useState } from "react";

export type ChipOption = { id: string; name: string };

export function ChipPicker({
  value,
  onChange,
  options,
  disabledIds = [],
  ready,
  label,
  prefix = "@",
}: {
  value: string | null;
  onChange: (id: string) => void;
  options: ChipOption[];
  disabledIds?: (string | null)[];
  ready: boolean;
  label: string;
  prefix?: string;
}) {
  const [open, setOpen] = useState(false);
  const selected = options.find((o) => o.id === value) ?? null;

  if (!ready) {
    return (
      <div>
        <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
          ⟳ syncing with discord…
        </p>
        <div className="mt-2 flex gap-2">
          <span className="h-7 w-24 animate-pulse rounded-chip bg-paper2" />
          <span className="h-7 w-20 animate-pulse rounded-chip bg-paper2" />
        </div>
      </div>
    );
  }

  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={selected ? `${label}: ${selected.name}` : `Select ${label}`}
        className="px-3 py-2 rounded-chip border-[1.5px] border-ink bg-paper2 text-[14px] font-extrabold shadow-rest"
      >
        {selected ? `${prefix}${selected.name}` : `select ${label.toLowerCase()}…`}
      </button>
      {open && (
        <ul className="mt-2 flex flex-wrap gap-2">
          {options.map((o) => {
            const disabled = disabledIds.includes(o.id);
            return (
              <li key={o.id}>
                <button
                  type="button"
                  disabled={disabled}
                  onClick={() => {
                    if (disabled) return;
                    onChange(o.id);
                    setOpen(false);
                  }}
                  className={
                    "px-3 py-1.5 rounded-chip border-[1.5px] border-ink text-[13.5px] font-extrabold " +
                    (disabled
                      ? "bg-paper3 text-mute line-through cursor-not-allowed"
                      : "bg-paper hover:bg-paper2")
                  }
                >
                  {o.name}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && npm run test -- ChipPicker`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ui/ChipPicker.tsx frontend/src/__tests__/components/ChipPicker.test.tsx
git commit -m "feat(ui): add ChipPicker primitive with disabled + skeleton states"
```

---

## Task 13: `SegmentedSelector` primitive

**Files:**
- Create: `frontend/src/components/ui/SegmentedSelector.tsx`
- Create: `frontend/src/__tests__/components/SegmentedSelector.test.tsx`

- [ ] **Step 1: Write failing test**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { SegmentedSelector } from "@/components/ui/SegmentedSelector";

describe("SegmentedSelector", () => {
  it("renders options and emits on click", () => {
    const onChange = vi.fn();
    render(
      <SegmentedSelector
        value={7}
        onChange={onChange}
        options={[
          { value: 7, label: "7 days" },
          { value: 14, label: "14 days" },
          { value: 90, label: "90 days", defaultPill: true },
        ]}
      />,
    );
    fireEvent.click(screen.getByRole("radio", { name: /14 days/i }));
    expect(onChange).toHaveBeenCalledWith(14);
  });

  it("marks the active option with aria-checked=true", () => {
    render(
      <SegmentedSelector
        value={7}
        onChange={vi.fn()}
        options={[{ value: 7, label: "7 days" }, { value: 14, label: "14 days" }]}
      />,
    );
    expect(screen.getByRole("radio", { name: /7 days/i })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: /14 days/i })).toHaveAttribute("aria-checked", "false");
  });

  it("renders defaultPill marker on flagged option", () => {
    render(
      <SegmentedSelector
        value={7}
        onChange={vi.fn()}
        options={[
          { value: 7, label: "7 days" },
          { value: 90, label: "90 days", defaultPill: true },
        ]}
      />,
    );
    expect(screen.getByText(/default/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npm run test -- SegmentedSelector`
Expected: FAIL.

- [ ] **Step 3: Implement**

```tsx
"use client";

export type SegmentedOption<T> = {
  value: T;
  label: string;
  defaultPill?: boolean;
};

export function SegmentedSelector<T extends string | number>({
  value,
  onChange,
  options,
  ariaLabel,
}: {
  value: T;
  onChange: (v: T) => void;
  options: SegmentedOption<T>[];
  ariaLabel?: string;
}) {
  return (
    <div role="radiogroup" aria-label={ariaLabel} className="flex flex-wrap gap-2">
      {options.map((o) => {
        const active = o.value === value;
        return (
          <button
            key={String(o.value)}
            type="button"
            role="radio"
            aria-checked={active}
            aria-label={o.label}
            onClick={() => onChange(o.value)}
            className={
              "relative px-4 py-2 rounded-chip border-[1.5px] border-ink text-[14px] font-extrabold " +
              (active ? "bg-ink text-paper shadow-rest" : "bg-paper2 text-ink hover:bg-paper3")
            }
          >
            {o.label}
            {o.defaultPill && (
              <span className="absolute -top-2 -right-2 bg-leaf text-ink text-[10px] font-extrabold px-1.5 py-0.5 rounded-chip border-[1.5px] border-ink">
                DEFAULT
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
```

- [ ] **Step 4: Run to verify pass**

Run: `cd frontend && npm run test -- SegmentedSelector`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ui/SegmentedSelector.tsx frontend/src/__tests__/components/SegmentedSelector.test.tsx
git commit -m "feat(ui): add SegmentedSelector primitive"
```

---

## Task 14: `CFSliderLight` primitive

**Files:**
- Create: `frontend/src/components/ui/CFSliderLight.tsx`
- Create: `frontend/src/__tests__/components/CFSliderLight.test.tsx`

- [ ] **Step 1: Write failing test**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { CFSliderLight } from "@/components/ui/CFSliderLight";

describe("CFSliderLight", () => {
  it("emits the new value on input change", () => {
    const onChange = vi.fn();
    render(<CFSliderLight value={5} min={1} max={10} onChange={onChange} />);
    const slider = screen.getByRole("slider");
    fireEvent.change(slider, { target: { value: "8" } });
    expect(onChange).toHaveBeenCalledWith(8);
  });

  it("renders tick row 1..10 with active tick highlighted", () => {
    render(<CFSliderLight value={3} min={1} max={10} onChange={vi.fn()} />);
    const three = screen.getByTestId("tick-3");
    const five = screen.getByTestId("tick-5");
    expect(three.className).toMatch(/leafDk/);
    expect(five.className).not.toMatch(/leafDk/);
  });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npm run test -- CFSliderLight`
Expected: FAIL.

- [ ] **Step 3: Implement**

```tsx
"use client";

export function CFSliderLight({
  value,
  min = 1,
  max = 10,
  onChange,
  disabled = false,
}: {
  value: number;
  min?: number;
  max?: number;
  onChange: (v: number) => void;
  disabled?: boolean;
}) {
  const ticks: number[] = [];
  for (let i = min; i <= max; i++) ticks.push(i);

  return (
    <div className="select-none">
      <input
        type="range"
        min={min}
        max={max}
        step={1}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
        aria-valuenow={value}
        aria-valuemin={min}
        aria-valuemax={max}
        className="w-full accent-leaf"
      />
      <div className="mt-2 flex justify-between font-extrabold text-[13px] tabular-nums">
        {ticks.map((n) => (
          <span
            key={n}
            data-testid={`tick-${n}`}
            className={n === value ? "text-leafDk" : "text-ink"}
          >
            {n}
          </span>
        ))}
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run to verify pass**

Run: `cd frontend && npm run test -- CFSliderLight`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ui/CFSliderLight.tsx frontend/src/__tests__/components/CFSliderLight.test.tsx
git commit -m "feat(ui): add CFSliderLight primitive"
```

---

## Task 15: `MapPreview` placeholder

**Files:**
- Create: `frontend/src/components/ui/MapPreview.tsx`

- [ ] **Step 1: Implement directly (trivial enough to skip a TDD round; rendering is asserted indirectly by the PrimaryLocationCard test)**

```tsx
export function MapPreview({ label }: { label: string }) {
  return (
    <div className="bg-paper2 border-b-[1.5px] border-ink flex flex-col items-center justify-center py-10">
      <span className="text-[40px]" role="img" aria-label="location pin">📍</span>
      <span className="mt-2 text-[12.5px] font-semibold text-mute lowercase">
        {label || "no location set"}
      </span>
    </div>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/components/ui/MapPreview.tsx
git commit -m "feat(ui): add MapPreview placeholder for primary location card"
```

---

## Task 16: Emoji catalog and `RsvpEmojiCard`

**Files:**
- Create: `frontend/src/components/guild/settings/emojiCatalog.ts`
- Create: `frontend/src/components/guild/settings/RsvpEmojiCard.tsx`
- Create: `frontend/src/__tests__/components/RsvpEmojiCard.test.tsx`

- [ ] **Step 1: Create the emoji catalog**

```ts
export const EMOJI_GOING = ["✅", "👍", "🙌", "🎉", "✨", "🦝", "🐧", "🦔", "🌱", "🪴", "🌻", "🍀"];
export const EMOJI_NO    = ["❌", "👎", "🙅", "😢", "💔", "🙃", "🦨", "🌧️", "🥱", "😴", "🚫", "⛔"];
export const EMOJI_MAYBE = ["❓", "🤔", "🤷", "🪙", "🌗", "🌫️", "🕰️", "🦊", "🐸", "🧭", "📅", "💭"];
```

- [ ] **Step 2: Write failing test**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { RsvpEmojiCard } from "@/components/guild/settings/RsvpEmojiCard";

describe("RsvpEmojiCard", () => {
  it("renders three slots with the current selections", () => {
    render(
      <RsvpEmojiCard
        value={{ accept: "✅", decline: "❌", maybe: "❓" }}
        onChange={vi.fn()}
      />,
    );
    expect(screen.getByLabelText(/going slot selection/i)).toHaveTextContent("✅");
    expect(screen.getByLabelText(/not going slot selection/i)).toHaveTextContent("❌");
    expect(screen.getByLabelText(/maybe slot selection/i)).toHaveTextContent("❓");
  });

  it("changes a slot when a swatch is clicked", () => {
    const onChange = vi.fn();
    render(
      <RsvpEmojiCard
        value={{ accept: "✅", decline: "❌", maybe: "❓" }}
        onChange={onChange}
      />,
    );
    const going = screen.getByLabelText(/going swatches/i);
    fireEvent.click(within(going).getByText("👍"));
    expect(onChange).toHaveBeenCalledWith({ accept: "👍", decline: "❌", maybe: "❓" });
  });
});
```

Use `import { within } from "@testing-library/react";` at the top.

- [ ] **Step 3: Run to verify failure**

Run: `cd frontend && npm run test -- RsvpEmojiCard`
Expected: FAIL.

- [ ] **Step 4: Implement**

```tsx
"use client";

import { EMOJI_GOING, EMOJI_NO, EMOJI_MAYBE } from "./emojiCatalog";

type Value = { accept: string; decline: string; maybe: string };

const SLOTS = [
  { key: "accept" as const, eyebrow: "going",     bg: "bg-rsvpGoing", catalog: EMOJI_GOING },
  { key: "decline" as const, eyebrow: "not going", bg: "bg-rsvpNo",    catalog: EMOJI_NO },
  { key: "maybe" as const,   eyebrow: "maybe",     bg: "bg-rsvpMaybe", catalog: EMOJI_MAYBE },
];

export function RsvpEmojiCard({
  value,
  onChange,
}: {
  value: Value;
  onChange: (v: Value) => void;
}) {
  return (
    <section className="bg-paper2 border-[1.5px] border-ink rounded-[16px] shadow-[5px_5px_0_#0E100D] p-6">
      <h2 className="text-[24px] font-extrabold tracking-[-0.03em] lowercase">rsvp emoji</h2>
      <p className="text-[13.5px] font-semibold text-mute mt-1">
        three reactions members tap. peepbot tallies them every minute.
      </p>
      <div className="grid grid-cols-3 gap-2.5 mt-4">
        {SLOTS.map((slot) => (
          <div key={slot.key} className={`${slot.bg} border-[1.5px] border-ink rounded-[12px] p-3`}>
            <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
              {slot.eyebrow}
            </p>
            <p
              className="text-[32px] mt-1"
              aria-label={`${slot.eyebrow} slot selection`}
            >
              {value[slot.key]}
            </p>
            <div
              className="grid grid-cols-4 gap-1 mt-2"
              aria-label={`${slot.eyebrow} swatches`}
            >
              {slot.catalog.map((e) => {
                const active = e === value[slot.key];
                return (
                  <button
                    key={e}
                    type="button"
                    onClick={() => onChange({ ...value, [slot.key]: e })}
                    className={
                      "rounded-chip border-[1.5px] border-ink py-1 text-[18px] " +
                      (active ? "bg-leaf" : "bg-paper hover:bg-paper2")
                    }
                  >
                    {e}
                  </button>
                );
              })}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
```

- [ ] **Step 5: Run to verify pass**

Run: `cd frontend && npm run test -- RsvpEmojiCard`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/guild/settings/emojiCatalog.ts frontend/src/components/guild/settings/RsvpEmojiCard.tsx frontend/src/__tests__/components/RsvpEmojiCard.test.tsx
git commit -m "feat(settings): add RsvpEmojiCard with swatch picker"
```

---

## Task 17: Hooks for live directory data + kick-bot mutator

**Files:**
- Modify: `frontend/src/lib/hooks.ts`
- Modify: `frontend/src/mocks/handlers.ts`

- [ ] **Step 1: Inspect existing hook patterns**

Run: `grep -n "useGuildSettings\|useGuilds\|useSWR" frontend/src/lib/hooks.ts | head -10`

Note the pattern: a thin `useSWR` wrapper around `apiGet`. Copy that pattern.

- [ ] **Step 2: Add `useGuildRoles`, `useGuildCategories`, `kickBotFromGuild`**

Append:

```ts
export type DirectoryEntry = { id: string; name: string };

export function useGuildRoles(guildId: string) {
  return useSWR<DirectoryEntry[]>(
    `/guild/${guildId}/roles`,
    () => apiGet<DirectoryEntry[]>(`/guild/${guildId}/roles`),
  );
}

export function useGuildCategories(guildId: string) {
  return useSWR<DirectoryEntry[]>(
    `/guild/${guildId}/categories`,
    () => apiGet<DirectoryEntry[]>(`/guild/${guildId}/categories`),
  );
}

export async function kickBotFromGuild(guildId: string, confirmGuildName: string): Promise<void> {
  await apiDelete(`/guild/${guildId}`, { confirmGuildName });
}
```

If `apiDelete` doesn't exist in `lib/api.ts`, add it next to `apiPatch`/`apiPost` with the same CSRF flow.

- [ ] **Step 3: Extend `GuildSettings` type returned by `useGuildSettings`**

In whatever type file holds it (`grep -rn "primaryLocationPlaceId" frontend/src/lib`), add:

```ts
  plannedCategoryId: string | null;
  archivedCategoryId: string | null;
  archiveDays: number;
  anyoneCanCreate: boolean;
```

And add the same fields to the request body type used by `updateGuildSettings`.

- [ ] **Step 4: Extend MSW handlers**

In `frontend/src/mocks/handlers.ts`, add handlers for the three new endpoints:

```ts
  http.get(`${API_BASE}/guild/:guildId/roles`, () =>
    HttpResponse.json([
      { id: "10", name: "events" },
      { id: "20", name: "event-organiser" },
      { id: "30", name: "mods" },
    ]),
  ),
  http.get(`${API_BASE}/guild/:guildId/categories`, () =>
    HttpResponse.json([
      { id: "100", name: "Active events" },
      { id: "200", name: "Archive" },
    ]),
  ),
  http.delete(`${API_BASE}/guild/:guildId`, async ({ request }) => {
    const body = (await request.json()) as { confirmGuildName: string };
    if (!body.confirmGuildName) return new HttpResponse(null, { status: 400 });
    return new HttpResponse(null, { status: 204 });
  }),
```

Extend the existing `GET/PATCH /guild/:guildId/settings` handlers (in the same file) to return / accept the four new fields.

- [ ] **Step 5: Run frontend tests**

Run: `cd frontend && npm run test`
Expected: PASS (existing tests should still pass; new hooks aren't exercised yet).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/hooks.ts frontend/src/mocks/handlers.ts frontend/src/lib/api.ts
git commit -m "feat(api-client): hooks for roles, categories, kick-bot"
```

---

## Task 18: `CategoriesArchiveCard` (with cross-validation)

**Files:**
- Create: `frontend/src/components/guild/settings/CategoriesArchiveCard.tsx`
- Create: `frontend/src/__tests__/components/CategoriesArchiveCard.test.tsx`

- [ ] **Step 1: Write failing test for cross-validation**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { CategoriesArchiveCard } from "@/components/guild/settings/CategoriesArchiveCard";

const cats = [
  { id: "100", name: "Alpha" },
  { id: "200", name: "Beta" },
  { id: "300", name: "Gamma" },
];

describe("CategoriesArchiveCard", () => {
  it("marks the planned-selected category as disabled in the archived picker", () => {
    render(
      <CategoriesArchiveCard
        plannedCategoryId="100"
        archivedCategoryId={null}
        archiveDays={90}
        categories={cats}
        ready
        onPlannedChange={vi.fn()}
        onArchivedChange={vi.fn()}
        onArchiveDaysChange={vi.fn()}
      />,
    );
    // open the archived picker
    fireEvent.click(screen.getByLabelText(/select archived events category/i));
    const alpha = screen.getByText("Alpha");
    expect(alpha).toHaveClass("line-through");
  });

  it("invokes onArchiveDaysChange when a segment is clicked", () => {
    const onArchiveDaysChange = vi.fn();
    render(
      <CategoriesArchiveCard
        plannedCategoryId={null}
        archivedCategoryId={null}
        archiveDays={90}
        categories={cats}
        ready
        onPlannedChange={vi.fn()}
        onArchivedChange={vi.fn()}
        onArchiveDaysChange={onArchiveDaysChange}
      />,
    );
    fireEvent.click(screen.getByRole("radio", { name: /7 days/i }));
    expect(onArchiveDaysChange).toHaveBeenCalledWith(7);
  });

  it("flags 90 days with the DEFAULT pill", () => {
    render(
      <CategoriesArchiveCard
        plannedCategoryId={null}
        archivedCategoryId={null}
        archiveDays={90}
        categories={cats}
        ready
        onPlannedChange={vi.fn()}
        onArchivedChange={vi.fn()}
        onArchiveDaysChange={vi.fn()}
      />,
    );
    expect(screen.getByText(/default/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npm run test -- CategoriesArchiveCard`
Expected: FAIL.

- [ ] **Step 3: Implement**

```tsx
"use client";

import { ChipPicker, type ChipOption } from "@/components/ui/ChipPicker";
import { SegmentedSelector } from "@/components/ui/SegmentedSelector";

export function CategoriesArchiveCard({
  plannedCategoryId,
  archivedCategoryId,
  archiveDays,
  categories,
  ready,
  onPlannedChange,
  onArchivedChange,
  onArchiveDaysChange,
}: {
  plannedCategoryId: string | null;
  archivedCategoryId: string | null;
  archiveDays: number;
  categories: ChipOption[];
  ready: boolean;
  onPlannedChange: (id: string) => void;
  onArchivedChange: (id: string) => void;
  onArchiveDaysChange: (d: number) => void;
}) {
  return (
    <section className="bg-white border-[1.5px] border-ink rounded-[16px] shadow-[4px_4px_0_#0E100D] p-6">
      <h2 className="text-[24px] font-extrabold tracking-[-0.03em] lowercase">categories &amp; archive</h2>
      <p className="text-[13.5px] font-semibold text-mute mt-1">
        where new event channels live, and where they retire to.
      </p>
      <div className="flex flex-col gap-[18px] mt-4">
        <div>
          <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
            planned events category
          </p>
          <ChipPicker
            value={plannedCategoryId}
            onChange={onPlannedChange}
            options={categories}
            disabledIds={[archivedCategoryId]}
            ready={ready}
            label="planned events category"
            prefix="#"
          />
          <p className="text-[12.5px] font-semibold text-mute mt-1">
            every new event becomes a channel inside this category.
          </p>
        </div>

        <div>
          <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
            archived events category
          </p>
          <ChipPicker
            value={archivedCategoryId}
            onChange={onArchivedChange}
            options={categories}
            disabledIds={[plannedCategoryId]}
            ready={ready}
            label="archived events category"
            prefix="#"
          />
          <p className="text-[12.5px] font-semibold text-mute mt-1">past events move here.</p>
        </div>

        <div>
          <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
            archive events after
          </p>
          <SegmentedSelector
            value={archiveDays}
            onChange={onArchiveDaysChange}
            ariaLabel="archive events after"
            options={[
              { value: 7, label: "7 days" },
              { value: 14, label: "14 days" },
              { value: 30, label: "30 days" },
              { value: 90, label: "90 days", defaultPill: true },
            ]}
          />
          <p className="text-[12.5px] font-semibold text-mute mt-2">
            after this many days past the event date, peepbot moves the channel into the archive
            category.
          </p>
        </div>
      </div>
    </section>
  );
}
```

- [ ] **Step 4: Run to verify pass**

Run: `cd frontend && npm run test -- CategoriesArchiveCard`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/guild/settings/CategoriesArchiveCard.tsx frontend/src/__tests__/components/CategoriesArchiveCard.test.tsx
git commit -m "feat(settings): categories + archive retention card"
```

---

## Task 19: `PrimaryLocationCard`

**Files:**
- Create: `frontend/src/components/guild/settings/PrimaryLocationCard.tsx`

- [ ] **Step 1: Implement (wraps existing `LocationAutocomplete` + `MapPreview`; no new behaviour worth its own test)**

```tsx
"use client";

import { LocationAutocomplete } from "@/components/ui/LocationAutocomplete";
import { MapPreview } from "@/components/ui/MapPreview";

export function PrimaryLocationCard({
  value,
  onChange,
  onPick,
  locationBias,
}: {
  value: string;
  onChange: (v: string) => void;
  onPick: (placeId: string, display: string) => void;
  locationBias?: { lat: number; lng: number };
}) {
  return (
    <section className="bg-white border-[1.5px] border-ink rounded-[16px] shadow-[4px_4px_0_#0E100D] overflow-hidden">
      <MapPreview label={value} />
      <div className="p-[18px_22px]">
        <h2 className="text-[24px] font-extrabold tracking-[-0.03em] lowercase">primary location</h2>
        <p className="text-[13.5px] font-semibold text-mute mt-1">
          biases venue search toward your group&apos;s area
        </p>
        <div className="mt-3">
          <LocationAutocomplete
            value={value}
            onChange={onChange}
            onPick={onPick}
            placeholder="e.g. Melbourne, VIC"
            locationBias={locationBias}
          />
          <p className="text-[12.5px] font-semibold text-mute mt-1">
            start typing to search google places.
          </p>
        </div>
      </div>
    </section>
  );
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/components/guild/settings/PrimaryLocationCard.tsx
git commit -m "feat(settings): primary location card with map preview"
```

---

## Task 20: `RolesPermissionsCard` and `CreationThrottleCard`

**Files:**
- Create: `frontend/src/components/guild/settings/RolesPermissionsCard.tsx`
- Create: `frontend/src/components/guild/settings/CreationThrottleCard.tsx`
- Create: `frontend/src/__tests__/components/RolesPermissionsCard.test.tsx`

- [ ] **Step 1: Failing test — selecting "organisers only" notifies caller**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { RolesPermissionsCard } from "@/components/guild/settings/RolesPermissionsCard";

const roles = [
  { id: "events", name: "events" },
  { id: "organisers", name: "organisers" },
];

describe("RolesPermissionsCard", () => {
  it("calls onAnyoneCanCreateChange when toggling who-can-create", () => {
    const onAnyone = vi.fn();
    render(
      <RolesPermissionsCard
        notifRole="events"
        organiserRole="organisers"
        anyoneCanCreate={true}
        roles={roles}
        ready
        onNotifRoleChange={vi.fn()}
        onOrganiserRoleChange={vi.fn()}
        onAnyoneCanCreateChange={onAnyone}
      />,
    );
    fireEvent.click(screen.getByRole("radio", { name: /organisers/i }));
    expect(onAnyone).toHaveBeenCalledWith(false);
  });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npm run test -- RolesPermissionsCard`
Expected: FAIL.

- [ ] **Step 3: Implement `RolesPermissionsCard`**

`notifRole` / `organiserRole` are role NAMES (per spec). The ChipPicker operates on `id`, so map name ↔ id locally:

```tsx
"use client";

import { ChipPicker, type ChipOption } from "@/components/ui/ChipPicker";
import { SegmentedSelector } from "@/components/ui/SegmentedSelector";

export function RolesPermissionsCard({
  notifRole,
  organiserRole,
  anyoneCanCreate,
  roles,
  ready,
  onNotifRoleChange,
  onOrganiserRoleChange,
  onAnyoneCanCreateChange,
}: {
  notifRole: string;
  organiserRole: string;
  anyoneCanCreate: boolean;
  roles: ChipOption[];
  ready: boolean;
  onNotifRoleChange: (name: string) => void;
  onOrganiserRoleChange: (name: string) => void;
  onAnyoneCanCreateChange: (v: boolean) => void;
}) {
  const idForName = (name: string) => roles.find((r) => r.name === name)?.id ?? null;
  const nameForId = (id: string) => roles.find((r) => r.id === id)?.name ?? id;

  return (
    <section className="bg-white border-[1.5px] border-ink rounded-[16px] shadow-[4px_4px_0_#0E100D] p-6">
      <h2 className="text-[24px] font-extrabold tracking-[-0.03em] lowercase">roles &amp; permissions</h2>
      <p className="text-[13.5px] font-semibold text-mute mt-1">who pings, who runs the show</p>

      <div className="flex flex-col gap-[18px] mt-4">
        <div>
          <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
            notification role
          </p>
          <ChipPicker
            value={idForName(notifRole)}
            onChange={(id) => onNotifRoleChange(nameForId(id))}
            options={roles}
            ready={ready}
            label="notification role"
          />
          <p className="text-[12.5px] font-semibold text-mute mt-1">
            this role gets pinged on every new event.
          </p>
        </div>

        <div>
          <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
            organiser role
          </p>
          <ChipPicker
            value={idForName(organiserRole)}
            onChange={(id) => onOrganiserRoleChange(nameForId(id))}
            options={roles}
            ready={ready}
            label="organiser role"
          />
          <p className="text-[12.5px] font-semibold text-mute mt-1">
            cancel · recategorize · kick attendees · create private channels
          </p>
        </div>

        <div>
          <p className="text-[11.5px] font-extrabold tracking-[0.18em] text-mute uppercase">
            who can create / edit events
          </p>
          <SegmentedSelector
            value={anyoneCanCreate ? "anyone" : "organisers"}
            onChange={(v) => onAnyoneCanCreateChange(v === "anyone")}
            ariaLabel="who can create or edit events"
            options={[
              { value: "anyone", label: "anyone" },
              { value: "organisers", label: "organisers" },
            ]}
          />
          <p className="text-[12.5px] font-semibold text-mute mt-2">
            {anyoneCanCreate
              ? "any server member can create events."
              : `only @${organiserRole} can create or edit events.`}
          </p>
        </div>
      </div>
    </section>
  );
}
```

- [ ] **Step 4: Implement `CreationThrottleCard`**

```tsx
"use client";

import { CFSliderLight } from "@/components/ui/CFSliderLight";

export function CreationThrottleCard({
  rateLimit,
  onRateLimitChange,
}: {
  rateLimit: number;
  onRateLimitChange: (n: number) => void;
}) {
  return (
    <section className="bg-white border-[1.5px] border-ink rounded-[16px] shadow-[4px_4px_0_#0E100D] p-6">
      <h2 className="text-[24px] font-extrabold tracking-[-0.03em] lowercase">creation throttle</h2>
      <p className="text-[13.5px] font-semibold text-mute mt-1">
        prevents one member from spamming new events. organisers always bypass this.
      </p>
      <div className="mt-4">
        <p className="text-[13.5px] font-semibold text-mute tabular-nums">
          <span className="text-[32px] font-extrabold text-ink tracking-[-0.03em] align-baseline">
            {rateLimit}
          </span>{" "}
          events / hr, per member
        </p>
        <div className="mt-3">
          <CFSliderLight value={rateLimit} min={1} max={10} onChange={onRateLimitChange} />
        </div>
      </div>
    </section>
  );
}
```

- [ ] **Step 5: Run tests**

Run: `cd frontend && npm run test -- "RolesPermissionsCard|CreationThrottleCard"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/guild/settings/RolesPermissionsCard.tsx frontend/src/components/guild/settings/CreationThrottleCard.tsx frontend/src/__tests__/components/RolesPermissionsCard.test.tsx
git commit -m "feat(settings): roles/permissions and creation throttle cards"
```

---

## Task 21: `DangerZoneCard` + `KickBotConfirmModal`

**Files:**
- Create: `frontend/src/components/guild/settings/DangerZoneCard.tsx`
- Create: `frontend/src/components/guild/settings/KickBotConfirmModal.tsx`
- Create: `frontend/src/__tests__/components/KickBotConfirmModal.test.tsx`

- [ ] **Step 1: Failing test — confirm button is disabled until typed match**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { KickBotConfirmModal } from "@/components/guild/settings/KickBotConfirmModal";

describe("KickBotConfirmModal", () => {
  it("keeps confirm disabled until the typed name matches", () => {
    const onConfirm = vi.fn();
    render(
      <KickBotConfirmModal
        guildName="Porch Pigeons"
        onClose={vi.fn()}
        onConfirm={onConfirm}
      />,
    );
    const confirm = screen.getByRole("button", { name: /kick peepbot/i });
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "porch pigeons" } });
    expect(confirm).not.toBeDisabled();
    fireEvent.click(confirm);
    expect(onConfirm).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Implement `KickBotConfirmModal`**

```tsx
"use client";

import { useState } from "react";
import { Chunky } from "@/components/ui/Chunky";

export function KickBotConfirmModal({
  guildName,
  onClose,
  onConfirm,
}: {
  guildName: string;
  onClose: () => void;
  onConfirm: () => void | Promise<void>;
}) {
  const [typed, setTyped] = useState("");
  const matches = typed.trim().toLowerCase() === guildName.trim().toLowerCase();

  return (
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 bg-ink/40 flex items-center justify-center z-30"
    >
      <div className="bg-paper border-[1.5px] border-danger rounded-[16px] shadow-[4px_4px_0_#DC2626] p-6 w-[420px]">
        <h2 className="text-[20px] font-extrabold tracking-[-0.02em] lowercase text-danger">
          kick peepbot
        </h2>
        <p className="mt-2 text-[14px] text-ink">
          type <strong>{guildName}</strong> to confirm. peepbot will leave the server, the events
          role will be deleted, and #outings will be removed. event history stays in the rewind.
        </p>
        <input
          type="text"
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          className="w-full mt-3 px-3 py-2 rounded-input border-[1.5px] border-ink"
          aria-label="confirm guild name"
        />
        <div className="mt-4 flex justify-end gap-2">
          <Chunky type="button" variant="ghost" onClick={onClose}>cancel</Chunky>
          <Chunky
            type="button"
            variant="paper"
            disabled={!matches}
            onClick={() => onConfirm()}
            style={{ borderColor: "#DC2626", color: "#DC2626" }}
          >
            kick peepbot
          </Chunky>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Implement `DangerZoneCard`**

```tsx
"use client";

import { useState } from "react";
import { Chunky } from "@/components/ui/Chunky";
import { KickBotConfirmModal } from "./KickBotConfirmModal";

export function DangerZoneCard({
  guildName,
  onKick,
}: {
  guildName: string;
  onKick: (confirmGuildName: string) => Promise<void> | void;
}) {
  const [showModal, setShowModal] = useState(false);
  return (
    <section className="bg-white border-[1.5px] border-danger rounded-[16px] shadow-[4px_4px_0_#DC2626] p-[18px]">
      <Chunky
        type="button"
        variant="paper"
        size="md"
        onClick={() => setShowModal(true)}
        style={{ borderColor: "#DC2626", color: "#DC2626", width: "100%" }}
      >
        kick peepbot
      </Chunky>
      {showModal && (
        <KickBotConfirmModal
          guildName={guildName}
          onClose={() => setShowModal(false)}
          onConfirm={async () => {
            await onKick(guildName);
            setShowModal(false);
          }}
        />
      )}
    </section>
  );
}
```

- [ ] **Step 4: Run tests**

Run: `cd frontend && npm run test -- "KickBotConfirmModal"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/guild/settings/DangerZoneCard.tsx frontend/src/components/guild/settings/KickBotConfirmModal.tsx frontend/src/__tests__/components/KickBotConfirmModal.test.tsx
git commit -m "feat(settings): danger zone card with typed-confirmation modal"
```

---

## Task 22: `StickySaveBar` with dirty tracking

**Files:**
- Create: `frontend/src/components/guild/settings/StickySaveBar.tsx`
- Create: `frontend/src/__tests__/components/StickySaveBar.test.tsx`

- [ ] **Step 1: Failing test**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { StickySaveBar } from "@/components/guild/settings/StickySaveBar";

describe("StickySaveBar", () => {
  it("shows all-saved copy when not dirty and disables save", () => {
    render(<StickySaveBar dirty={false} onDiscard={vi.fn()} onSave={vi.fn()} submitting={false} />);
    expect(screen.getByText(/all saved/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /save changes/i })).toBeDisabled();
  });

  it("shows unsaved-changes copy when dirty and routes onSave", () => {
    const onSave = vi.fn();
    render(<StickySaveBar dirty onDiscard={vi.fn()} onSave={onSave} submitting={false} />);
    expect(screen.getByText(/unsaved changes/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));
    expect(onSave).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Implement**

```tsx
"use client";

import { Chunky } from "@/components/ui/Chunky";

export function StickySaveBar({
  dirty,
  submitting,
  onDiscard,
  onSave,
}: {
  dirty: boolean;
  submitting: boolean;
  onDiscard: () => void;
  onSave: () => void;
}) {
  return (
    <div className="sticky bottom-6 mx-auto bg-white border-[1.5px] border-ink rounded-[16px] shadow-[4px_4px_0_#0E100D] px-5 py-3 flex items-center justify-between gap-3 z-10">
      <button
        type="button"
        onClick={onDiscard}
        disabled={!dirty}
        className="text-[14px] font-semibold text-mute hover:text-ink disabled:opacity-50"
      >
        discard
      </button>
      <span className="text-[13px] font-semibold text-mute flex items-center gap-2">
        <span
          aria-hidden
          className={
            "inline-block w-2 h-2 rounded-full " + (dirty ? "bg-leaf" : "bg-paper3")
          }
        />
        {dirty ? "unsaved changes" : "all saved"}
      </span>
      <Chunky
        type="button"
        variant="leaf"
        size="md"
        disabled={!dirty || submitting}
        onClick={onSave}
      >
        {submitting ? "saving…" : "save changes"}
      </Chunky>
    </div>
  );
}
```

- [ ] **Step 3: Run tests**

Run: `cd frontend && npm run test -- StickySaveBar`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/guild/settings/StickySaveBar.tsx frontend/src/__tests__/components/StickySaveBar.test.tsx
git commit -m "feat(settings): sticky save bar with dirty indicator"
```

---

## Task 23: Rewrite `GuildSettingsForm.tsx`

**Files:**
- Modify: `frontend/src/components/guild/GuildSettingsForm.tsx`
- Modify: `frontend/src/__tests__/components/GuildSettingsForm.test.tsx`
- Modify: `frontend/src/__tests__/render/GuildSettingsForm.test.tsx`

- [ ] **Step 1: Update component test for the new layout**

Replace the existing test body. The new test covers: cards render once the data loads, the throttle card is conditional, and the kick-bot flow wires through to the API. Keep using the existing MSW handlers.

```tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders } from "@/__tests__/utils/renderWithProviders";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { GuildSettingsForm } from "@/components/guild/GuildSettingsForm";

describe("GuildSettingsForm (redesign)", () => {
  it("renders the two-column layout with all base cards", async () => {
    renderWithProviders(<GuildSettingsForm guildId="g1" />);
    expect(await screen.findByRole("heading", { name: /rsvp emoji/i })).toBeVisible();
    expect(screen.getByRole("heading", { name: /categories & archive/i })).toBeVisible();
    expect(screen.getByRole("heading", { name: /primary location/i })).toBeVisible();
    expect(screen.getByRole("heading", { name: /roles & permissions/i })).toBeVisible();
    // Throttle card visible by default because anyoneCanCreate defaults to true.
    expect(screen.getByRole("heading", { name: /creation throttle/i })).toBeVisible();
  });

  it("removes the throttle card when 'organisers' is selected", async () => {
    renderWithProviders(<GuildSettingsForm guildId="g1" />);
    await screen.findByRole("heading", { name: /roles & permissions/i });
    fireEvent.click(screen.getByRole("radio", { name: /organisers/i }));
    await waitFor(() =>
      expect(screen.queryByRole("heading", { name: /creation throttle/i })).toBeNull(),
    );
  });
});
```

(`renderWithProviders` is whatever helper the project already uses — `grep -rn "renderWithProviders\|MemoryRouter" frontend/src/__tests__/utils`. If no helper exists, render directly with the MSW server enabled.)

- [ ] **Step 2: Update render test copy**

Open `frontend/src/__tests__/render/GuildSettingsForm.test.tsx` and replace any assertions for the old tab labels ("Roles & channels", "RSVP emoji", "Defaults & limits") with the new card headings. Keep the loading/error branches.

- [ ] **Step 3: Run render + component tests**

Run: `cd frontend && npm run test -- GuildSettingsForm`
Expected: FAIL (component not yet rewritten).

- [ ] **Step 4: Rewrite `GuildSettingsForm.tsx`**

Replace the entire file body. Key behaviour:

```tsx
"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { CategoriesArchiveCard } from "@/components/guild/settings/CategoriesArchiveCard";
import { CreationThrottleCard } from "@/components/guild/settings/CreationThrottleCard";
import { DangerZoneCard } from "@/components/guild/settings/DangerZoneCard";
import { PrimaryLocationCard } from "@/components/guild/settings/PrimaryLocationCard";
import { RolesPermissionsCard } from "@/components/guild/settings/RolesPermissionsCard";
import { RsvpEmojiCard } from "@/components/guild/settings/RsvpEmojiCard";
import { StickySaveBar } from "@/components/guild/settings/StickySaveBar";
import {
  kickBotFromGuild,
  updateGuildSettings,
  useCurrentUser,
  useGuildCategories,
  useGuildRoles,
  useGuildSettings,
  useGuilds,
} from "@/lib/hooks";
import { UnauthorizedError } from "@/lib/api";
import {
  fetchPlaceDetails,
  geocodePlace,
  newPlacesSessionToken,
} from "@/lib/places";

type State = {
  primaryLocation: string;
  primaryLocationPlaceId: string | null;
  primaryLocationLat: number | null;
  primaryLocationLng: number | null;
  notifRole: string;
  organiserRole: string;
  anyoneCanCreate: boolean;
  rateLimit: number;
  plannedCategoryId: string | null;
  archivedCategoryId: string | null;
  archiveDays: 7 | 14 | 30 | 90;
  emojiAccepted: string;
  emojiDeclined: string;
  emojiMaybe: string;
};

export function GuildSettingsForm({ guildId }: { guildId: string }) {
  const router = useRouter();
  const { data: user } = useCurrentUser();
  const { data: guilds } = useGuilds();
  const guild = guilds?.find((g) => g.id === guildId) ?? null;
  const { data: settings, isLoading, error } = useGuildSettings(guildId);
  const { data: roles, isLoading: rolesLoading } = useGuildRoles(guildId);
  const { data: categories, isLoading: categoriesLoading } = useGuildCategories(guildId);
  const sessionToken = useMemo(newPlacesSessionToken, []);

  const [state, setState] = useState<State | null>(null);
  const [initial, setInitial] = useState<State | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (settings && state === null) {
      const snapshot: State = {
        primaryLocation: settings.primaryLocationName ?? "",
        primaryLocationPlaceId: settings.primaryLocationPlaceId ?? null,
        primaryLocationLat: settings.primaryLocationLat ?? null,
        primaryLocationLng: settings.primaryLocationLng ?? null,
        notifRole: settings.eventsRole ?? "events",
        organiserRole: settings.organiserRole ?? "event-organiser",
        anyoneCanCreate: settings.anyoneCanCreate,
        rateLimit:
          settings.eventCreateRateLimitPerHour ??
          settings.defaultEventCreateRateLimitPerHour ??
          5,
        plannedCategoryId: settings.plannedCategoryId,
        archivedCategoryId: settings.archivedCategoryId,
        archiveDays: settings.archiveDays as 7 | 14 | 30 | 90,
        emojiAccepted: settings.emojiAccepted ?? "✅",
        emojiDeclined: settings.emojiDeclined ?? "❌",
        emojiMaybe: settings.emojiMaybe ?? "❓",
      };
      setState(snapshot);
      setInitial(snapshot);
    }
  }, [settings, state]);

  useEffect(() => {
    if (user && !user.ownedGuildIds?.includes(guildId)) router.push("/");
  }, [user, router, guildId]);

  if (isLoading && !settings && !error) {
    return <div className="p-8 text-mute">loading…</div>;
  }
  if (error) {
    const isUnauthorized = error instanceof UnauthorizedError;
    return (
      <div className="mx-auto max-w-[640px] px-4 py-16 text-center">
        <h1 className="text-[32px] font-extrabold tracking-[-0.04em]">
          {isUnauthorized ? "access denied" : "couldn't load settings"}
        </h1>
      </div>
    );
  }
  if (!settings || !state || !initial) {
    return <div className="p-8 text-mute">loading…</div>;
  }

  const dirty = JSON.stringify(state) !== JSON.stringify(initial);

  const handleLocationPick = async (placeId: string, displayValue: string) => {
    setState({
      ...state,
      primaryLocation: displayValue,
      primaryLocationPlaceId: placeId,
    });
    fetchPlaceDetails(placeId, sessionToken);
    const coords = await geocodePlace(placeId, sessionToken);
    if (coords) {
      setState((s) => (s ? { ...s, primaryLocationLat: coords.lat, primaryLocationLng: coords.lng } : s));
    }
  };

  const onSave = async () => {
    setSubmitting(true);
    try {
      const updated = await updateGuildSettings(guildId, {
        primaryLocationPlaceId: state.primaryLocationPlaceId,
        primaryLocationName: state.primaryLocation.trim() || null,
        primaryLocationLat: state.primaryLocationLat,
        primaryLocationLng: state.primaryLocationLng,
        eventsRole: state.notifRole,
        organiserRole: state.organiserRole,
        emojiAccepted: state.emojiAccepted,
        emojiDeclined: state.emojiDeclined,
        emojiMaybe: state.emojiMaybe,
        anyoneCanCreate: state.anyoneCanCreate,
        eventCreateRateLimitPerHour: state.anyoneCanCreate ? state.rateLimit : null,
        plannedCategoryId: state.plannedCategoryId,
        archivedCategoryId: state.archivedCategoryId,
        archiveDays: state.archiveDays,
      });
      const next: State = { ...state };
      setInitial(next);
      void updated;
    } finally {
      setSubmitting(false);
    }
  };

  const onKick = async (confirmGuildName: string) => {
    await kickBotFromGuild(guildId, confirmGuildName);
    router.push("/");
  };

  const locationBias =
    guild?.primaryLocationLat != null && guild?.primaryLocationLng != null
      ? { lat: guild.primaryLocationLat, lng: guild.primaryLocationLng }
      : undefined;

  return (
    <div className="px-14 pt-8 pb-14">
      <header className="mb-6">
        <h1 className="text-[36px] font-extrabold tracking-[-0.03em] lowercase">
          {guild?.name ?? "server config"}
        </h1>
        <p className="text-[13.5px] font-semibold text-mute mt-1">
          edit settings for {guild?.name?.toLowerCase() ?? "this server"}
          {typeof guild?.memberCount === "number" ? ` · ${guild.memberCount} members` : ""}
        </p>
      </header>

      <div className="flex gap-5 items-start">
        <div className="flex-[1.2] flex flex-col gap-5">
          <RsvpEmojiCard
            value={{ accept: state.emojiAccepted, decline: state.emojiDeclined, maybe: state.emojiMaybe }}
            onChange={(v) =>
              setState({ ...state, emojiAccepted: v.accept, emojiDeclined: v.decline, emojiMaybe: v.maybe })
            }
          />
          <CategoriesArchiveCard
            plannedCategoryId={state.plannedCategoryId}
            archivedCategoryId={state.archivedCategoryId}
            archiveDays={state.archiveDays}
            categories={categories ?? []}
            ready={!categoriesLoading}
            onPlannedChange={(id) => setState({ ...state, plannedCategoryId: id })}
            onArchivedChange={(id) => setState({ ...state, archivedCategoryId: id })}
            onArchiveDaysChange={(d) => setState({ ...state, archiveDays: d as 7 | 14 | 30 | 90 })}
          />
        </div>

        <div className="flex-1 flex flex-col gap-5">
          <PrimaryLocationCard
            value={state.primaryLocation}
            onChange={(v) =>
              setState({
                ...state,
                primaryLocation: v,
                primaryLocationPlaceId: null,
                primaryLocationLat: null,
                primaryLocationLng: null,
              })
            }
            onPick={handleLocationPick}
            locationBias={locationBias}
          />
          <RolesPermissionsCard
            notifRole={state.notifRole}
            organiserRole={state.organiserRole}
            anyoneCanCreate={state.anyoneCanCreate}
            roles={roles ?? []}
            ready={!rolesLoading}
            onNotifRoleChange={(n) => setState({ ...state, notifRole: n })}
            onOrganiserRoleChange={(n) => setState({ ...state, organiserRole: n })}
            onAnyoneCanCreateChange={(v) => setState({ ...state, anyoneCanCreate: v })}
          />
          {state.anyoneCanCreate && (
            <CreationThrottleCard
              rateLimit={state.rateLimit}
              onRateLimitChange={(n) => setState({ ...state, rateLimit: n })}
            />
          )}
          <DangerZoneCard guildName={guild?.name ?? ""} onKick={onKick} />
        </div>
      </div>

      <StickySaveBar
        dirty={dirty}
        submitting={submitting}
        onDiscard={() => setState(initial)}
        onSave={onSave}
      />
    </div>
  );
}
```

Notes:
- The hash-based tab routing is dropped entirely. No backward-compat redirect — `#tab=…` becomes inert.
- `confirm()` prompt on organiser-role change is dropped (the spec doesn't call for it; the typed-confirmation modal is reserved for the kick action).
- Cancel link / back nav lived in the old `<header>` — wire it back if your `HeaderBar` doesn't already provide it.

- [ ] **Step 5: Run tests**

Run: `cd frontend && npm run test -- GuildSettingsForm`
Expected: PASS.

- [ ] **Step 6: Run typecheck + lint**

Run: `cd frontend && npm run typecheck && npm run lint`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/guild/GuildSettingsForm.tsx frontend/src/__tests__/components/GuildSettingsForm.test.tsx frontend/src/__tests__/render/GuildSettingsForm.test.tsx
git commit -m "feat(settings): rebuild GuildSettingsForm two-column layout"
```

---

## Task 24: Cross-validation integration test

**Files:**
- Create: `frontend/src/__tests__/components/GuildSettingsForm.crossValidation.test.tsx`

- [ ] **Step 1: Write the test**

```tsx
import { describe, it, expect } from "vitest";
import { renderWithProviders } from "@/__tests__/utils/renderWithProviders";
import { screen, fireEvent, waitFor } from "@testing-library/react";
import { GuildSettingsForm } from "@/components/guild/GuildSettingsForm";

describe("GuildSettingsForm cross-validation", () => {
  it("marks the planned-selected category as disabled in the archived picker", async () => {
    renderWithProviders(<GuildSettingsForm guildId="g1" />);
    await screen.findByRole("heading", { name: /categories & archive/i });

    // Select Alpha as planned
    fireEvent.click(screen.getByLabelText(/select planned events category/i));
    fireEvent.click(screen.getByText("Active events"));

    // Open archived picker — Active events should be struck-through
    fireEvent.click(screen.getByLabelText(/select archived events category/i));
    await waitFor(() => {
      const strike = screen.getAllByText("Active events").find((el) =>
        el.className.includes("line-through"),
      );
      expect(strike).toBeTruthy();
    });
  });
});
```

- [ ] **Step 2: Run**

Run: `cd frontend && npm run test -- crossValidation`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/__tests__/components/GuildSettingsForm.crossValidation.test.tsx
git commit -m "test(settings): cross-validation between planned and archived categories"
```

---

## Task 25: Kick-bot integration test

**Files:**
- Create: `frontend/src/__tests__/components/GuildSettingsForm.kickBot.test.tsx`

- [ ] **Step 1: Write the test**

```tsx
import { describe, it, expect, vi } from "vitest";
import { renderWithProviders } from "@/__tests__/utils/renderWithProviders";
import { screen, fireEvent, waitFor } from "@testing-library/react";
import { GuildSettingsForm } from "@/components/guild/GuildSettingsForm";
import { server } from "@/mocks/server";
import { http, HttpResponse } from "msw";

describe("GuildSettingsForm kick-bot", () => {
  it("calls DELETE /guild/:id with the typed name on confirm", async () => {
    const calls: Array<{ confirmGuildName: string }> = [];
    server.use(
      http.delete(/\/guild\/g1$/, async ({ request }) => {
        calls.push((await request.json()) as { confirmGuildName: string });
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderWithProviders(<GuildSettingsForm guildId="g1" />);
    await screen.findByRole("button", { name: /kick peepbot/i });
    fireEvent.click(screen.getByRole("button", { name: /kick peepbot/i }));

    // Modal open: confirm disabled until typed
    const confirm = screen.getByRole("button", { name: /^kick peepbot$/i });
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByRole("textbox", { name: /confirm guild name/i }), {
      target: { value: "porch pigeons" },   // matches fixture guild name
    });
    expect(confirm).not.toBeDisabled();
    fireEvent.click(confirm);

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]).toEqual({ confirmGuildName: "porch pigeons" });
  });
});
```

This assumes the fixture guild's name is "Porch Pigeons" — verify by reading `frontend/src/mocks/fixtures.ts` and adjust the typed text if the fixture uses a different name.

- [ ] **Step 2: Run**

Run: `cd frontend && npm run test -- kickBot`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/__tests__/components/GuildSettingsForm.kickBot.test.tsx
git commit -m "test(settings): kick-bot confirm modal calls DELETE /guild/:id"
```

---

## Task 26: Manual browser verification

- [ ] **Step 1: Boot the database**

Run: `docker-compose up -d`

- [ ] **Step 2: Boot the backend with the local profile**

In a new terminal, from `backend/`, run the bootRun command from `CLAUDE.md`.

- [ ] **Step 3: Boot the frontend against the live backend**

```bash
cd frontend
NEXT_PUBLIC_API_MODE=live npm run dev
```

- [ ] **Step 4: Walk the happy path**

Navigate to `http://localhost:3000/dashboard/<your-guild-id>/settings` and verify:
- All six cards render in the documented column positions.
- Toggling "organisers" removes the throttle card from the DOM.
- Selecting the same Discord category in both planned and archived shows strike-through on the second picker (and the backend rejects with 400 if attempted on save — open devtools network panel).
- Save bar transitions from "all saved" → "unsaved changes" on any edit; discard reverts.
- Clicking "kick peepbot" opens a modal; the confirm button stays disabled until the typed name matches.

- [ ] **Step 5: Run the full frontend suite once more**

Run: `cd frontend && npm run test && npm run typecheck && npm run lint && npm run build`
Expected: all PASS.

- [ ] **Step 6: Run the full backend suite once more**

Run: `cd backend && ./gradlew test spotlessCheck`
Expected: all PASS.

- [ ] **Step 7: No additional commit if green — push the branch and open a PR**

Use the existing project convention. Commit messages already conform to Conventional Commits.

---

## Self-review summary

- **Spec coverage:** Every spec section is mapped — schema (T1-2), DTO/request (T3), validation (T4), directory endpoints (T5), kick endpoint (T6), DiscordService rewiring (T7), scheduler (T8), listener (T9), tokens (T11), primitives (T12-15), cards (T16-20), modal (T21), save bar (T22), form rewrite (T23), cross-validation (T24), kick UI (T25), manual verification (T26).
- **Type consistency:** `DirectoryEntry` (record / TS type) carries the same `(id, name)` shape across backend + frontend. `ChipPicker`'s `value: string | null` matches `plannedCategoryId: string | null` in state. Hooks return the new fields and `updateGuildSettings` accepts them; the form's `State` mirrors the DTO.
- **No placeholders:** Every "TODO" / "TBD" / "add validation" pattern has been replaced with concrete code in this plan.
- **Risks flagged at execution time:**
  - `Fixtures.java` location not verified — Task 5/6 ask the implementer to confirm via `grep`.
  - The dev-tools comment in CLAUDE.md notes `application-local.yaml` may need datasource overrides; the manual verification step assumes that file is already configured.
  - If `Slab` doesn't accept a custom shadow class via `className`, Task 16/18/20 will need a tiny prop addition — handle inline if it surfaces.
