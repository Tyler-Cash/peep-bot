# Multi-Guild Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate per-guild configuration from `application.yaml` into the database, auto-onboard guilds via JDA `GuildJoinEvent`, drop the login-time membership gate so users with no joined guilds see an install CTA.

**Architecture:** Rename `guild_settings` → `guild`; the row IS the per-guild config. A new `GuildRegistrationService` upserts rows on startup (per `jda.getGuilds()`) and on `GuildJoinEvent`, then runs the existing init steps per guild. Per-guild emoji is resolved into a `Map<Long, ResolvedEmoji>` in memory. Role names (`events`, `event-admin`) and `separator_channel` come from the row, not yaml. `DiscordConfiguration.guildId` is deleted; `ContractConfiguration` gains a `guildId` field as the sole single-guild yaml consumer.

**Tech Stack:** Spring Boot 3.5.8 (Java 21), JDA 5.6.1, Liquibase, JPA/Hibernate, PostgreSQL, Spring Security/OAuth2, Next.js 15, SWR, MSW.

**Spec:** `docs/superpowers/specs/2026-04-29-multi-guild-design.md`

---

## File Map

**New backend files:**
- `backend/src/main/java/dev/tylercash/event/discord/Guild.java` (renamed from `GuildSettings.java`)
- `backend/src/main/java/dev/tylercash/event/discord/GuildRepository.java` (renamed from `GuildSettingsRepository.java`)
- `backend/src/main/java/dev/tylercash/event/discord/GuildRegistrationService.java`
- `backend/src/main/java/dev/tylercash/event/discord/GuildEmojiResolver.java`
- `backend/src/main/java/dev/tylercash/event/discord/listener/GuildLifecycleListener.java`
- `backend/src/test/java/dev/tylercash/event/discord/GuildRegistrationServiceTest.java`

**Modified backend files:**
- `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (new changeset at end)
- `backend/src/main/java/dev/tylercash/event/discord/DiscordConfiguration.java` (drop guildId, eventsRole, adminRole, seperatorChannel, Emoji)
- `backend/src/main/java/dev/tylercash/event/discord/DiscordInitializationService.java` (becomes per-guild helper)
- `backend/src/main/java/dev/tylercash/event/discord/DiscordAuthService.java` (admin role from Guild row)
- `backend/src/main/java/dev/tylercash/event/discord/DiscordService.java` (sort jobs iterate active guilds; emoji from resolver)
- `backend/src/main/java/dev/tylercash/event/discord/DiscordUserCacheService.java` (iterate active guilds in refresh)
- `backend/src/main/java/dev/tylercash/event/discord/GuildController.java` (drop yaml fallback)
- `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsController.java` (extend DTO/request, owner bypass)
- `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsDto.java`
- `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRequest.java`
- `backend/src/main/java/dev/tylercash/event/discord/AvatarController.java`
- `backend/src/main/java/dev/tylercash/event/security/oauth2/CustomOAuth2UserService.java` (drop membership gate)
- `backend/src/main/java/dev/tylercash/event/global/SecurityController.java` (return adminGuildIds[])
- `backend/src/main/java/dev/tylercash/event/security/UserInfoDto.java` (replace `admin: boolean` with `adminGuildIds: long[]`)
- `backend/src/main/java/dev/tylercash/event/contract/ContractConfiguration.java` (add `guildId`)
- `backend/src/main/java/dev/tylercash/event/contract/ContractService.java` (read guildId from contract config)

**Modified frontend files:**
- `frontend/src/lib/types.ts` (extend `GuildSettingsDto`, change UserInfoDto-equivalent)
- `frontend/src/lib/hooks.ts` (active-guild admin check uses `adminGuildIds`)
- `frontend/src/components/guild/GuildSettingsForm.tsx` (new fields)
- `frontend/src/app/page.tsx` (or whichever renders the event feed empty state — see Task 24)
- `frontend/src/__tests__/components/GuildSettingsForm.test.tsx`
- MSW handlers under `frontend/src/mocks/` (new fields + adminGuildIds)

---

## Phase 1 — Data layer

### Task 1: Liquibase changeset to migrate `guild_settings` → `guild`

**Files:**
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append new changeset before the final `- include:` line)

- [ ] **Step 1: Append the changeset**

Add this changeset immediately before the existing `- include: file: db/changelog/contract/db.changelog-contract.yaml` block (which is the final entry). Note that changesets after `create event_classification_attempt` should be the existing ones — append after the last existing changeset and before the include.

```yaml
  - changeSet:
      id: rename guild_settings to guild and add config columns
      author: tyler
      changes:
        - renameTable:
            oldTableName: guild_settings
            newTableName: guild
        - addColumn:
            tableName: guild
            columns:
              - column:
                  name: events_role
                  type: varchar(100)
                  defaultValue: "events"
                  constraints:
                    nullable: false
              - column:
                  name: admin_role
                  type: varchar(100)
                  defaultValue: "event-admin"
                  constraints:
                    nullable: false
              - column:
                  name: separator_channel
                  type: varchar(100)
              - column:
                  name: emoji_accepted
                  type: varchar(200)
                  defaultValue: "✅"
                  constraints:
                    nullable: false
              - column:
                  name: emoji_declined
                  type: varchar(200)
                  defaultValue: "❌"
                  constraints:
                    nullable: false
              - column:
                  name: emoji_maybe
                  type: varchar(200)
                  defaultValue: "❓"
                  constraints:
                    nullable: false
              - column:
                  name: joined_at
                  type: TIMESTAMP WITH TIME ZONE
                  defaultValueComputed: NOW()
                  constraints:
                    nullable: false
              - column:
                  name: active
                  type: boolean
                  defaultValueBoolean: true
                  constraints:
                    nullable: false
```

- [ ] **Step 2: Build to verify Liquibase parses the changeset**

Run from `backend/`:
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL. Liquibase YAML is parsed at runtime, not compile time, so this only catches structural Java problems — Step 4 below is the real test.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(db): rename guild_settings to guild and add per-guild config columns"
```

- [ ] **Step 4: Verify migration applies cleanly via Testcontainers run** (deferred — happens in Task 22's integration test)

---

### Task 2: Rename `GuildSettings` entity → `Guild` and add new fields

**Files:**
- Delete: `backend/src/main/java/dev/tylercash/event/discord/GuildSettings.java`
- Create: `backend/src/main/java/dev/tylercash/event/discord/Guild.java`
- Delete: `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRepository.java`
- Create: `backend/src/main/java/dev/tylercash/event/discord/GuildRepository.java`

- [ ] **Step 1: Create the `Guild` entity**

Note we're renaming the *class* but the JDA library also has a `net.dv8tion.jda.api.entities.Guild`. Keep this class in the `dev.tylercash.event.discord` package and qualify JDA `Guild` references in callers as needed. Where ambiguity is high, callers can keep using `net.dv8tion.jda.api.entities.Guild` fully qualified — see Task 9.

`backend/src/main/java/dev/tylercash/event/discord/Guild.java`:
```java
package dev.tylercash.event.discord;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "guild")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Guild {

    @Id
    @Column(name = "guild_id")
    private Long guildId;

    @Column(name = "events_role", nullable = false)
    private String eventsRole;

    @Column(name = "admin_role", nullable = false)
    private String adminRole;

    @Column(name = "separator_channel")
    private String separatorChannel;

    @Column(name = "emoji_accepted", nullable = false)
    private String emojiAccepted;

    @Column(name = "emoji_declined", nullable = false)
    private String emojiDeclined;

    @Column(name = "emoji_maybe", nullable = false)
    private String emojiMaybe;

    @Column(name = "primary_location_place_id")
    private String primaryLocationPlaceId;

    @Column(name = "primary_location_name")
    private String primaryLocationName;

    @Column(name = "primary_location_lat")
    private Double primaryLocationLat;

    @Column(name = "primary_location_lng")
    private Double primaryLocationLng;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    public static Guild withDefaults(long guildId) {
        Guild g = new Guild();
        g.setGuildId(guildId);
        g.setEventsRole("events");
        g.setAdminRole("event-admin");
        g.setEmojiAccepted("✅");
        g.setEmojiDeclined("❌");
        g.setEmojiMaybe("❓");
        g.setJoinedAt(Instant.now());
        g.setActive(true);
        return g;
    }
}
```

- [ ] **Step 2: Create the repository**

`backend/src/main/java/dev/tylercash/event/discord/GuildRepository.java`:
```java
package dev.tylercash.event.discord;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuildRepository extends JpaRepository<Guild, Long> {
    List<Guild> findAllByActiveTrue();
}
```

- [ ] **Step 3: Delete the old files**

```bash
rm backend/src/main/java/dev/tylercash/event/discord/GuildSettings.java
rm backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRepository.java
```

- [ ] **Step 4: Compile (will fail — callers reference the deleted classes; we fix them in subsequent tasks)**

```bash
cd backend && ./gradlew compileJava 2>&1 | tail -30
```
Expected: compile errors in `GuildController`, `GuildSettingsController`, possibly tests. Note them; they'll be fixed in Tasks 8, 17, etc. **Do not commit yet** — keep this rolled together with the next task.

---

### Task 3: Wire `GuildController` and `GuildSettingsController` to the renamed entity (compile-fix only, no behavior change)

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/GuildController.java`
- Modify: `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsController.java`

- [ ] **Step 1: Update `GuildController` field + import**

In `GuildController.java`, replace:
```java
import ...; // remove GuildSettingsRepository import
private final GuildSettingsRepository settingsRepository;
```
with:
```java
private final GuildRepository guildRepository;
```

Replace the `toDto` body's `GuildSettings settings = settingsRepository.findById(...)` with `Guild settings = guildRepository.findById(Long.parseLong(guild.getId())).orElse(null);` — note `Guild` here refers to the new `dev.tylercash.event.discord.Guild` entity. JDA's `net.dv8tion.jda.api.entities.Guild` is already imported at the top; rename one of them to a fully qualified reference to avoid the clash. Easiest: alias the entity by leaving JDA's import alone and referring to ours as `dev.tylercash.event.discord.Guild` inline. Concretely:

```java
private dev.tylercash.event.discord.Guild lookupSettings(net.dv8tion.jda.api.entities.Guild guild) {
    return guildRepository.findById(Long.parseLong(guild.getId())).orElse(null);
}

private GuildDto toDto(net.dv8tion.jda.api.entities.Guild guild) {
    String name = guild.getName();
    dev.tylercash.event.discord.Guild settings = lookupSettings(guild);
    return new GuildDto(
            guild.getId(),
            name,
            deriveInitials(name),
            guild.getIconUrl(),
            deriveColor(guild.getId()),
            settings != null ? settings.getSeparatorChannel() : "",
            guild.getMemberCount(),
            settings != null ? settings.getPrimaryLocationLat() : null,
            settings != null ? settings.getPrimaryLocationLng() : null);
}
```

Note: previously the `channel` (separator) field came from `discordConfiguration.getSeperatorChannel()`. It now comes from the per-guild row.

- [ ] **Step 2: Update `GuildSettingsController` field type and inline dto mapping**

Replace `private final GuildSettingsRepository settingsRepository;` with `private final GuildRepository guildRepository;`. Replace usages `settingsRepository.findById(guildId)` with `guildRepository.findById(guildId)`. Replace `new GuildSettings(guildId, null, null, null, null)` with `Guild.withDefaults(guildId)`. Where the type was `GuildSettings`, change to `Guild` (qualify if it shadows JDA's Guild — `dev.tylercash.event.discord.Guild` — but `GuildSettingsController` doesn't import JDA's Guild today, so a plain `Guild` is fine).

Existing `getSettings` and `updateSettings` only read/write the four `primaryLocation*` fields — leave the controller-side projection as-is for now (Task 17 extends it).

- [ ] **Step 3: Compile**

```bash
cd backend && ./gradlew compileJava 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/
git commit -m "refactor(discord): rename GuildSettings entity to Guild"
```

---

## Phase 2 — Per-guild emoji + role lookup

### Task 4: `GuildEmojiResolver` (new) — per-guild emoji caching

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/GuildEmojiResolver.java`
- Create: `backend/src/test/java/dev/tylercash/event/discord/GuildEmojiResolverTest.java`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/dev/tylercash/event/discord/GuildEmojiResolverTest.java`:
```java
package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import org.junit.jupiter.api.Test;

class GuildEmojiResolverTest {

    @Test
    void resolveUsesUnicodeWhenNoCustomEmojiMatches() {
        net.dv8tion.jda.api.entities.Guild jdaGuild = mock(net.dv8tion.jda.api.entities.Guild.class);
        when(jdaGuild.getIdLong()).thenReturn(1L);
        when(jdaGuild.getEmojisByName(eq("accepted"), eq(true))).thenReturn(List.of());
        when(jdaGuild.getEmojisByName(eq("declined"), eq(true))).thenReturn(List.of());
        when(jdaGuild.getEmojisByName(eq("maybe"), eq(true))).thenReturn(List.of());

        Guild row = Guild.withDefaults(1L);
        GuildEmojiResolver resolver = new GuildEmojiResolver();
        resolver.resolve(jdaGuild, row);

        GuildEmojiResolver.ResolvedEmoji e = resolver.forGuild(1L);
        assertThat(e.accepted()).isEqualTo("✅");
        assertThat(e.declined()).isEqualTo("❌");
        assertThat(e.maybe()).isEqualTo("❓");
    }

    @Test
    void resolveUsesCustomEmojiMentionWhenAvailable() {
        net.dv8tion.jda.api.entities.Guild jdaGuild = mock(net.dv8tion.jda.api.entities.Guild.class);
        when(jdaGuild.getIdLong()).thenReturn(2L);
        RichCustomEmoji custom = mock(RichCustomEmoji.class);
        when(custom.getAsMention()).thenReturn("<:accepted:99>");
        when(jdaGuild.getEmojisByName(eq("accepted"), eq(true))).thenReturn(List.of(custom));
        when(jdaGuild.getEmojisByName(eq("declined"), eq(true))).thenReturn(List.of());
        when(jdaGuild.getEmojisByName(eq("maybe"), eq(true))).thenReturn(List.of());

        Guild row = Guild.withDefaults(2L);
        GuildEmojiResolver resolver = new GuildEmojiResolver();
        resolver.resolve(jdaGuild, row);

        assertThat(resolver.forGuild(2L).accepted()).isEqualTo("<:accepted:99>");
    }

    @Test
    void forGuildFallsBackToUnicodeWhenNotResolved() {
        GuildEmojiResolver resolver = new GuildEmojiResolver();
        GuildEmojiResolver.ResolvedEmoji e = resolver.forGuild(404L);
        assertThat(e.accepted()).isEqualTo("✅");
        assertThat(e.declined()).isEqualTo("❌");
        assertThat(e.maybe()).isEqualTo("❓");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./gradlew test --tests GuildEmojiResolverTest 2>&1 | tail -10
```
Expected: compile failure (no `GuildEmojiResolver`).

- [ ] **Step 3: Implement `GuildEmojiResolver`**

`backend/src/main/java/dev/tylercash/event/discord/GuildEmojiResolver.java`:
```java
package dev.tylercash.event.discord;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GuildEmojiResolver {

    public record ResolvedEmoji(String accepted, String declined, String maybe) {}

    private static final ResolvedEmoji DEFAULT =
            new ResolvedEmoji("✅", "❌", "❓");

    private final java.util.Map<Long, ResolvedEmoji> byGuild = new ConcurrentHashMap<>();

    public void resolve(net.dv8tion.jda.api.entities.Guild jdaGuild, Guild row) {
        long id = jdaGuild.getIdLong();
        String accepted = pick(jdaGuild, "accepted", row.getEmojiAccepted());
        String declined = pick(jdaGuild, "declined", row.getEmojiDeclined());
        String maybe = pick(jdaGuild, "maybe", row.getEmojiMaybe());
        byGuild.put(id, new ResolvedEmoji(accepted, declined, maybe));
        log.info("Resolved emoji for guild {}: {} {} {}", id, accepted, declined, maybe);
    }

    public void evict(long guildId) {
        byGuild.remove(guildId);
    }

    public ResolvedEmoji forGuild(long guildId) {
        return byGuild.getOrDefault(guildId, DEFAULT);
    }

    private String pick(net.dv8tion.jda.api.entities.Guild jdaGuild, String name, String fallback) {
        List<RichCustomEmoji> matches = jdaGuild.getEmojisByName(name, true);
        if (!matches.isEmpty()) {
            return matches.getFirst().getAsMention();
        }
        return fallback;
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd backend && ./gradlew test --tests GuildEmojiResolverTest 2>&1 | tail -10
```
Expected: 3 tests passing.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/GuildEmojiResolver.java backend/src/test/java/dev/tylercash/event/discord/GuildEmojiResolverTest.java
git commit -m "feat(discord): add GuildEmojiResolver for per-guild emoji"
```

---

### Task 5: Switch `DiscordService` emoji + role usages to per-guild lookups

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/DiscordService.java`

The current code reads `discordConfiguration.getEmoji().getAccepted()` (etc.) when building event message buttons, and reads `discordConfiguration.getEventsRole()` when assembling roles to mention. Both must become per-guild.

- [ ] **Step 1: Inject `GuildEmojiResolver` and `GuildRepository`**

Replace the field block with these additions and remove `discordConfiguration`'s sole-purpose-of-emoji usages later in this task:
```java
private final GuildEmojiResolver guildEmojiResolver;
private final GuildRepository guildRepository;
```
(Lombok `@AllArgsConstructor` picks them up.)

- [ ] **Step 2: Update `postEventMessage`**

Currently:
```java
List<Role> rolesToMention =
        discordRoleService.getRolesByName(channel.getGuild().getIdLong(), discordConfiguration.getEventsRole());
```
becomes:
```java
long guildId = channel.getGuild().getIdLong();
String eventsRole = guildRepository.findById(guildId)
        .map(Guild::getEventsRole)
        .orElse("events");
List<Role> rolesToMention = discordRoleService.getRolesByName(guildId, eventsRole);

GuildEmojiResolver.ResolvedEmoji emoji = guildEmojiResolver.forGuild(guildId);
```

Then replace each:
```java
Button.secondary(ACCEPTED, discordConfiguration.getEmoji().getAccepted())
Button.secondary(DECLINED, discordConfiguration.getEmoji().getDeclined())
Button.secondary(MAYBE, discordConfiguration.getEmoji().getMaybe())
```
with:
```java
Button.secondary(ACCEPTED, emoji.accepted())
Button.secondary(DECLINED, emoji.declined())
Button.secondary(MAYBE, emoji.maybe())
```

- [ ] **Step 3: Update `sortActiveChannels` and `sortArchiveChannels` (scheduled jobs)**

Currently they call `discordConfiguration.getGuildId()` and `discordConfiguration.getSeperatorChannel()` — they need to iterate every active guild and use the per-guild separator.

Replace the bodies of both `@Scheduled` methods:
```java
@Observed(name = "discord.sort-active-channels")
@Scheduled(fixedDelay = 5, timeUnit = MINUTES)
public void sortActiveChannels() {
    for (Guild row : guildRepository.findAllByActiveTrue()) {
        try {
            Category category = getEventCategory(row.getGuildId());
            sortChannelsByEventDate(category, row.getSeparatorChannel() == null ? "" : row.getSeparatorChannel());
        } catch (Exception e) {
            log.warn("Failed to sort active channels for guild {}: {}", row.getGuildId(), e.getMessage());
        }
    }
}

@Observed(name = "discord.sort-archive-channels")
@Scheduled(fixedDelay = 5, timeUnit = MINUTES)
public void sortArchiveChannels() {
    for (Guild row : guildRepository.findAllByActiveTrue()) {
        try {
            Category category = discordChannelService.getCategoryByName(row.getGuildId(), EVENT_ARCHIVE_CATEGORY);
            sortChannelsByChannelName(category);
        } catch (Exception e) {
            log.warn("Failed to sort archive channels for guild {}: {}", row.getGuildId(), e.getMessage());
        }
    }
}
```

`Guild` here refers to `dev.tylercash.event.discord.Guild`. Note `DiscordService` already imports `net.dv8tion.jda.api.entities.Guild` — qualify the entity inline as `dev.tylercash.event.discord.Guild` to disambiguate.

- [ ] **Step 4: Update the orphan-archive block inside `sortChannelsByEventDate`**

It currently calls:
```java
Category archiveCategory = discordChannelService.getCategoryByName(discordConfiguration.getGuildId(), EVENT_ARCHIVE_CATEGORY);
```

Change the method signature to accept a `long guildId` (so the caller in Step 3 passes `row.getGuildId()`):
```java
public void sortChannelsByEventDate(Category eventCategory, String separator, long guildId) { ... }
```
and replace the inner `discordConfiguration.getGuildId()` with `guildId`. Update the loop in `sortActiveChannels` to pass `row.getGuildId()` as the third argument.

- [ ] **Step 5: Compile**

```bash
cd backend && ./gradlew compileJava 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run existing DiscordService tests if any**

```bash
cd backend && ./gradlew test --tests "*DiscordService*" 2>&1 | tail -20
```
Expected: pass (no behavioural test depends on the singleton-emoji path; verify nothing broke).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/DiscordService.java
git commit -m "refactor(discord): per-guild emoji and role lookups in DiscordService"
```

---

### Task 6: `DiscordAuthService.isEventAdmin` reads admin role from `Guild` row

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/DiscordAuthService.java`

- [ ] **Step 1: Add `GuildRepository` field; replace `discordConfiguration.getAdminRole()` lookup**

Replace the class with:
```java
package dev.tylercash.event.discord;

import dev.tylercash.event.security.dev.DevUserProperties;
import java.util.Optional;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class DiscordAuthService {
    private final JDA jda;
    private final GuildRepository guildRepository;
    private final Optional<DevUserProperties> devUserProperties;

    public Member getMember(long guildId, long userId) {
        try {
            return jda.getGuildById(guildId).retrieveMemberById(userId).complete();
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() == ErrorResponse.UNKNOWN_USER
                    || e.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER) {
                return null;
            }
            throw e;
        }
    }

    public boolean isMember(long guildId, long userId) {
        if (devUserProperties.isPresent() && devUserProperties.get().isForceAdmin()) {
            return true;
        }
        return getMember(guildId, userId) != null;
    }

    public boolean hasRole(long guildId, long userId, String roleName) {
        if (devUserProperties.isPresent() && devUserProperties.get().isForceAdmin()) {
            return true;
        }
        Member member = getMember(guildId, userId);
        return member != null
                && member.getRoles().stream().anyMatch(role -> role.getName().equalsIgnoreCase(roleName));
    }

    public boolean isEventAdmin(long guildId, long userId) {
        String adminRole = guildRepository
                .findById(guildId)
                .map(Guild::getAdminRole)
                .orElse("event-admin");
        return hasRole(guildId, userId, adminRole);
    }

    public boolean isGuildOwner(long guildId, long userId) {
        if (devUserProperties.isPresent() && devUserProperties.get().isForceAdmin()) {
            return true;
        }
        net.dv8tion.jda.api.entities.Guild jdaGuild = jda.getGuildById(guildId);
        if (jdaGuild == null) return false;
        return jdaGuild.getOwnerIdLong() == userId;
    }
}
```

`isGuildOwner` is the lockout escape hatch consumed by Task 18.

- [ ] **Step 2: Compile**

```bash
cd backend && ./gradlew compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/DiscordAuthService.java
git commit -m "refactor(discord): admin role lookup uses per-guild Guild row"
```

---

## Phase 3 — Lifecycle

### Task 7: `GuildRegistrationService` (new)

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/GuildRegistrationService.java`
- Create: `backend/src/test/java/dev/tylercash/event/discord/GuildRegistrationServiceTest.java`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/dev/tylercash/event/discord/GuildRegistrationServiceTest.java`:
```java
package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;
import net.dv8tion.jda.api.entities.Guild.MFALevel;
import org.junit.jupiter.api.Test;

class GuildRegistrationServiceTest {

    @Test
    void onboardCreatesRowWhenMissing() {
        GuildRepository repo = mock(GuildRepository.class);
        when(repo.findById(42L)).thenReturn(Optional.empty());
        DiscordInitializationService init = mock(DiscordInitializationService.class);
        GuildEmojiResolver emoji = mock(GuildEmojiResolver.class);

        net.dv8tion.jda.api.entities.Guild jdaGuild = mock(net.dv8tion.jda.api.entities.Guild.class);
        when(jdaGuild.getIdLong()).thenReturn(42L);
        when(jdaGuild.getName()).thenReturn("Test");

        GuildRegistrationService svc = new GuildRegistrationService(repo, init, emoji);
        svc.onboard(jdaGuild);

        verify(repo).save(argThat(g -> g.getGuildId() == 42L && g.isActive() && "events".equals(g.getEventsRole())));
        verify(init).initialise(eq(jdaGuild), any(Guild.class));
        verify(emoji).resolve(eq(jdaGuild), any(Guild.class));
    }

    @Test
    void onboardReactivatesDormantRow() {
        Guild row = Guild.withDefaults(7L);
        row.setActive(false);

        GuildRepository repo = mock(GuildRepository.class);
        when(repo.findById(7L)).thenReturn(Optional.of(row));
        DiscordInitializationService init = mock(DiscordInitializationService.class);
        GuildEmojiResolver emoji = mock(GuildEmojiResolver.class);

        net.dv8tion.jda.api.entities.Guild jdaGuild = mock(net.dv8tion.jda.api.entities.Guild.class);
        when(jdaGuild.getIdLong()).thenReturn(7L);

        GuildRegistrationService svc = new GuildRegistrationService(repo, init, emoji);
        svc.onboard(jdaGuild);

        verify(repo).save(argThat(g -> g.getGuildId() == 7L && g.isActive()));
    }

    @Test
    void deactivateMarksRowInactiveAndEvictsEmoji() {
        Guild row = Guild.withDefaults(99L);
        GuildRepository repo = mock(GuildRepository.class);
        when(repo.findById(99L)).thenReturn(Optional.of(row));
        DiscordInitializationService init = mock(DiscordInitializationService.class);
        GuildEmojiResolver emoji = mock(GuildEmojiResolver.class);

        GuildRegistrationService svc = new GuildRegistrationService(repo, init, emoji);
        svc.deactivate(99L);

        verify(repo).save(argThat(g -> !g.isActive()));
        verify(emoji).evict(99L);
    }
}
```

- [ ] **Step 2: Implement the service**

`backend/src/main/java/dev/tylercash/event/discord/GuildRegistrationService.java`:
```java
package dev.tylercash.event.discord;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuildRegistrationService {
    private final GuildRepository guildRepository;
    private final DiscordInitializationService discordInitializationService;
    private final GuildEmojiResolver guildEmojiResolver;

    @Transactional
    public void onboard(net.dv8tion.jda.api.entities.Guild jdaGuild) {
        long id = jdaGuild.getIdLong();
        Guild row = guildRepository.findById(id).orElseGet(() -> Guild.withDefaults(id));
        if (!row.isActive()) {
            log.info("Reactivating dormant guild row {}", id);
        }
        row.setActive(true);
        guildRepository.save(row);

        guildEmojiResolver.resolve(jdaGuild, row);
        discordInitializationService.initialise(jdaGuild, row);
    }

    @Transactional
    public void deactivate(long guildId) {
        guildRepository.findById(guildId).ifPresent(row -> {
            row.setActive(false);
            guildRepository.save(row);
        });
        guildEmojiResolver.evict(guildId);
        log.info("Marked guild {} as inactive", guildId);
    }
}
```

- [ ] **Step 3: Run test**

```bash
cd backend && ./gradlew test --tests GuildRegistrationServiceTest 2>&1 | tail -10
```

Expected: 3 tests passing. (Note Task 8 changes `DiscordInitializationService.initialise(...)` signature — until that's done, this test references a method that does not exist. If you're executing strictly in order, Task 8 must be done before this test runs. If running TDD per task, swap the order or stub `initialise` first.)

**Implementation note:** to keep this task's test green standalone, do Task 8 first, then come back to commit Task 7.

- [ ] **Step 4: Commit (after Task 8 lands)**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/GuildRegistrationService.java backend/src/test/java/dev/tylercash/event/discord/GuildRegistrationServiceTest.java
git commit -m "feat(discord): add GuildRegistrationService for upsert-on-join"
```

---

### Task 8: `DiscordInitializationService` becomes a per-guild helper

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/DiscordInitializationService.java`

- [ ] **Step 1: Replace the class**

```java
package dev.tylercash.event.discord;

import static dev.tylercash.event.discord.DiscordConfiguration.EVENT_ARCHIVE_CATEGORY;
import static dev.tylercash.event.discord.DiscordConfiguration.EVENT_CATEGORY;

import dev.tylercash.event.contract.ContractConfiguration;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@AllArgsConstructor
public class DiscordInitializationService {
    private final JDA jda;
    private final DiscordChannelService discordChannelService;
    private final ContractConfiguration contractConfig;
    private final DiscordUserCacheService discordUserCacheService;
    private final GuildRegistrationService guildRegistrationService;
    private final ContractGuildResolver contractGuildResolver;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Onboarding {} guilds on startup", jda.getGuilds().size());
        jda.getGuilds().forEach(guildRegistrationService::onboard);
    }

    /** Per-guild init invoked by GuildRegistrationService. Idempotent. */
    public void initialise(net.dv8tion.jda.api.entities.Guild jdaGuild, Guild row) {
        log.info("Initialising guild '{}' ({})", jdaGuild.getName(), jdaGuild.getIdLong());
        syncGuildMembers(jdaGuild);

        Category outings = ensureCategory(jdaGuild, EVENT_CATEGORY);
        ensureCategory(jdaGuild, EVENT_ARCHIVE_CATEGORY);
        if (contractGuildResolver.isContractsGuild(jdaGuild.getIdLong())) {
            ensureCategory(jdaGuild, contractConfig.getCategoryName());
        }
        ensureSeparatorChannel(outings, row.getSeparatorChannel());
    }

    private void syncGuildMembers(net.dv8tion.jda.api.entities.Guild guild) {
        log.info("Syncing members for guild '{}'...", guild.getName());
        try {
            guild.loadMembers()
                    .onSuccess(members -> {
                        members.forEach(member -> discordUserCacheService.registerIfMissing(
                                member.getId(),
                                DiscordUtil.getUserDisplayName(member),
                                member.getUser().getName(),
                                guild.getIdLong()));
                        log.info("Synced {} members for guild '{}'", members.size(), guild.getName());
                    })
                    .get();
        } catch (Exception e) {
            log.error("Failed to sync guild members", e);
        }
    }

    private Category ensureCategory(net.dv8tion.jda.api.entities.Guild guild, String categoryName) {
        return discordChannelService.getOrCreateCategory(guild, categoryName);
    }

    private void ensureSeparatorChannel(Category category, String separatorName) {
        if (separatorName == null || separatorName.isBlank()) {
            return;
        }
        List<TextChannel> channels = category.getTextChannels();
        boolean exists = channels.stream().anyMatch(ch -> ch.getName().equalsIgnoreCase(separatorName));
        if (exists) {
            log.info("Separator channel '{}' already exists in '{}'", separatorName, category.getName());
            return;
        }
        log.info("Creating separator channel '{}' in '{}'", separatorName, category.getName());
        category.createTextChannel(separatorName).setPosition(0).complete();
    }
}
```

Notes:
- The old emoji-resolution reflection (`resolveEmojiFields`) is gone — `GuildEmojiResolver` (Task 4) handles event-emoji per guild. Contract emoji still uses singleton `ContractConfiguration.Emoji`; if you want to retain custom-emoji resolution for contracts on the contracts guild, add a method back here that mutates `contractConfig.getEmoji()` only when `jdaGuild.getIdLong() == contractGuildResolver.getContractsGuildId()`. For this plan, drop the contract emoji resolution — contracts will use the unicode defaults from `ContractConfiguration.Emoji`. If that's unacceptable, file a follow-up.
- `ContractGuildResolver` is a new tiny bean from Task 9 — wraps `ContractConfiguration.guildId`.
- The startup loop circular-dependency concern: `DiscordInitializationService` depends on `GuildRegistrationService` which depends on `DiscordInitializationService`. Spring resolves this fine for fields/constructors with proxies as long as one is `@Lazy`. To be safe, mark the `GuildRegistrationService` field `@Lazy`:

```java
@Lazy
private final GuildRegistrationService guildRegistrationService;
```

Add `import org.springframework.context.annotation.Lazy;`.

- [ ] **Step 2: Compile**

```bash
cd backend && ./gradlew compileJava 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL (assumes Task 9's `ContractGuildResolver` exists — see Task 9 for the implementation, or stub it inline first).

- [ ] **Step 3: Commit (with Task 7's bundle)**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/DiscordInitializationService.java
git commit -m "refactor(discord): DiscordInitializationService becomes per-guild helper"
```

---

### Task 9: Add `dev.tylercash.contract.guild-id` and `ContractGuildResolver`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/contract/ContractConfiguration.java`
- Modify: `backend/src/main/java/dev/tylercash/event/contract/ContractService.java`
- Create: `backend/src/main/java/dev/tylercash/event/discord/ContractGuildResolver.java`

- [ ] **Step 1: Add `guildId` field to `ContractConfiguration`**

```java
@Data
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.contract")
public class ContractConfiguration {
    private long guildId;          // <-- NEW; required
    private String resolverRole = "prediction-resolver";
    // ... existing fields unchanged
}
```

- [ ] **Step 2: Update `ContractService`**

Replace `discordConfig.getGuildId()` (two occurrences in the file) with `contractConfig.getGuildId()`. Drop the `private final DiscordConfiguration discordConfig;` field if no other usages remain. Re-grep:

```bash
cd backend && grep -n discordConfig src/main/java/dev/tylercash/event/contract/ContractService.java
```
Expected: no remaining references.

- [ ] **Step 3: Create `ContractGuildResolver`**

`backend/src/main/java/dev/tylercash/event/discord/ContractGuildResolver.java`:
```java
package dev.tylercash.event.discord;

import dev.tylercash.event.contract.ContractConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContractGuildResolver {
    private final ContractConfiguration contractConfiguration;

    public long getContractsGuildId() {
        return contractConfiguration.getGuildId();
    }

    public boolean isContractsGuild(long guildId) {
        return contractConfiguration.getGuildId() == guildId;
    }
}
```

- [ ] **Step 4: Update yaml**

Modify `backend/src/main/resources/application-local.yaml` (gitignored) — instruct the developer in `CLAUDE.md` to add:
```yaml
dev.tylercash:
  contract:
    guild-id: <discord-guild-id>
```

(Don't commit `application-local.yaml`. The CLAUDE.md update is in Task 23.)

- [ ] **Step 5: Compile + tests**

```bash
cd backend && ./gradlew compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/contract/ContractConfiguration.java backend/src/main/java/dev/tylercash/event/contract/ContractService.java backend/src/main/java/dev/tylercash/event/discord/ContractGuildResolver.java
git commit -m "feat(contract): introduce dev.tylercash.contract.guild-id"
```

---

### Task 10: JDA listener for `GuildJoinEvent` / `GuildLeaveEvent`

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/listener/GuildLifecycleListener.java`
- Modify: `backend/src/main/java/dev/tylercash/event/discord/ClientConfiguration.java` (register listener)

- [ ] **Step 1: Create the listener**

`backend/src/main/java/dev/tylercash/event/discord/listener/GuildLifecycleListener.java`:
```java
package dev.tylercash.event.discord.listener;

import dev.tylercash.event.discord.GuildRegistrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GuildLifecycleListener extends ListenerAdapter {
    private final GuildRegistrationService guildRegistrationService;

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        log.info("Bot joined guild '{}' ({})", event.getGuild().getName(), event.getGuild().getIdLong());
        try {
            guildRegistrationService.onboard(event.getGuild());
        } catch (Exception e) {
            log.error("Onboarding failed for guild {}: {}", event.getGuild().getIdLong(), e.getMessage(), e);
        }
    }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        log.info("Bot left guild '{}' ({})", event.getGuild().getName(), event.getGuild().getIdLong());
        guildRegistrationService.deactivate(event.getGuild().getIdLong());
    }
}
```

- [ ] **Step 2: Register the listener**

Open `ClientConfiguration.java` (in `dev.tylercash.event.discord`). Currently it adds existing JDA listeners during JDA bean construction. Add `GuildLifecycleListener` to the same `addEventListeners(...)` call (or `jda.addEventListener(...)` post-construction). Concretely, inject the new listener in the same method that constructs the JDA bean and append it to the existing listener list.

If the existing pattern uses Spring auto-wiring of all `ListenerAdapter` beans into JDA, no change is needed — the `@Component` annotation makes it discoverable. Verify by searching:
```bash
grep -n "addEventListener" backend/src/main/java/dev/tylercash/event/discord/ClientConfiguration.java
```
If listeners are added explicitly there, append the new bean. Otherwise no change.

- [ ] **Step 3: Compile + run**

```bash
cd backend && ./gradlew compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/listener/GuildLifecycleListener.java backend/src/main/java/dev/tylercash/event/discord/ClientConfiguration.java
git commit -m "feat(discord): handle GuildJoinEvent / GuildLeaveEvent"
```

---

## Phase 4 — Strip `DiscordConfiguration` singleton-guild fields

### Task 11: `CustomOAuth2UserService` — drop membership gate; sync user across active guilds

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/security/oauth2/CustomOAuth2UserService.java`

- [ ] **Step 1: Replace the class body**

```java
package dev.tylercash.event.security.oauth2;

import dev.tylercash.event.discord.AvatarDownloadService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

@Slf4j
@Setter
@Component
@AllArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {
    private DiscordService discordService;
    private DiscordUserCacheService discordUserCacheService;
    private GuildRepository guildRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String snowflake = Objects.requireNonNull(oAuth2User.getAttribute("id"));
        String username = oAuth2User.getAttribute("username");
        String globalName = oAuth2User.getAttribute("global_name");
        log.info("Authenticated user {}", username);

        String avatarHash = oAuth2User.getAttribute("avatar");
        String avatarUrl = AvatarDownloadService.discordAvatarUrl(snowflake, avatarHash);
        String displayName = globalName != null ? globalName : (username != null ? username : snowflake);
        String name = username != null ? username : snowflake;
        long userId = Long.parseLong(snowflake);

        // Record membership in every active guild the bot shares with this user.
        // Users who are members of zero guilds still log in successfully — the
        // frontend renders an install CTA when GET /guild returns [].
        for (Guild row : guildRepository.findAllByActiveTrue()) {
            if (discordService.isUserMemberOfServer(row.getGuildId(), userId)) {
                discordUserCacheService.upsertUser(snowflake, displayName, name, avatarUrl, row.getGuildId());
            }
        }

        return oAuth2User;
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd backend && ./gradlew compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/security/oauth2/CustomOAuth2UserService.java
git commit -m "feat(auth): allow login for users outside any guild"
```

---

### Task 12: `SecurityController.userInfo` returns `adminGuildIds[]`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/security/UserInfoDto.java`
- Modify: `backend/src/main/java/dev/tylercash/event/global/SecurityController.java`

- [ ] **Step 1: Update DTO**

Open `UserInfoDto.java`. Replace the `boolean admin` field with `List<Long> adminGuildIds`. The existing record probably looks like:
```java
public record UserInfoDto(String username, String displayName, String snowflake, boolean admin, String avatarUrl) {}
```
Becomes:
```java
public record UserInfoDto(
        String username,
        String displayName,
        String snowflake,
        java.util.List<Long> adminGuildIds,
        String avatarUrl) {}
```

- [ ] **Step 2: Update `SecurityController`**

```java
package dev.tylercash.event.global;

import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.security.UserInfoDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@AllArgsConstructor
@RequestMapping(value = "/auth")
@Tag(name = "Authentication", description = "Authentication status operations")
public class SecurityController {
    private final DiscordService discordService;
    private final GuildRepository guildRepository;

    @Operation(summary = "Check authentication status", description = "Returns current user info if authenticated")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User is authenticated"),
        @ApiResponse(responseCode = "401", description = "User is not authenticated")
    })
    @GetMapping("/is-logged-in")
    public UserInfoDto isLoggedIn(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not logged in");
        }
        String userSnowflake = principal.getAttribute("id");
        String username = principal.getAttribute("username");
        String globalName = principal.getAttribute("global_name");
        String displayName = globalName != null ? globalName : username;
        long userId = Long.parseLong(userSnowflake);
        List<Long> adminGuildIds = guildRepository.findAllByActiveTrue().stream()
                .map(Guild::getGuildId)
                .filter(id -> discordService.isUserAdminOfServer(id, userId))
                .toList();
        return new UserInfoDto(username, displayName, userSnowflake, adminGuildIds, "/api/avatar/" + userSnowflake);
    }
}
```

- [ ] **Step 3: Compile**

```bash
cd backend && ./gradlew compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL. (Frontend type changes happen in Task 21.)

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/security/UserInfoDto.java backend/src/main/java/dev/tylercash/event/global/SecurityController.java
git commit -m "feat(auth): return per-guild adminGuildIds from /auth/is-logged-in"
```

---

### Task 13: `DiscordUserCacheService.refreshStaleEntries` — iterate active guilds

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/DiscordUserCacheService.java`

- [ ] **Step 1: Replace the constructor field type and the `refreshStaleEntries` body**

Replace `DiscordConfiguration discordConfiguration` with `GuildRepository guildRepository` (both in field, constructor params, and assignment).

Replace the body of `refreshStaleEntries`:
```java
@Observed(name = "discord.refresh-user-cache")
@Scheduled(fixedDelay = 60, timeUnit = SECONDS)
public void refreshStaleEntries() {
    Instant staleCutoff = Instant.now().minus(STALE_MINUTES, ChronoUnit.MINUTES);
    List<String> activeSnowflakes = new ArrayList<>(attendanceRepository.findAllDistinctSnowflakes());
    activeSnowflakes.addAll(eventRepository.findAllDistinctCreatorSnowflakes());
    if (activeSnowflakes.isEmpty()) {
        return;
    }

    Map<String, DiscordUserCache> cached = cacheRepository.findAllBySnowflakeIn(activeSnowflakes).stream()
            .collect(Collectors.toMap(DiscordUserCache::getSnowflake, Function.identity()));

    List<String> toRefresh = activeSnowflakes.stream()
            .filter(s -> {
                DiscordUserCache entry = cached.get(s);
                return entry == null || entry.getUpdatedAt().isBefore(staleCutoff);
            })
            .limit(REFRESH_BATCH_SIZE)
            .toList();

    List<Long> activeGuilds = guildRepository.findAllByActiveTrue().stream()
            .map(Guild::getGuildId)
            .toList();

    for (String snowflake : toRefresh) {
        for (long guildId : activeGuilds) {
            try {
                Member member = discordServiceProvider
                        .getObject()
                        .getMemberFromServer(guildId, Long.parseLong(snowflake));
                if (member != null) {
                    String displayName = DiscordUtil.getUserDisplayName(member);
                    String username = member.getUser().getName();
                    String avatarUrl = member.getEffectiveAvatar().getUrl(256);
                    upsertUser(snowflake, displayName, username, avatarUrl, guildId);
                    break; // first guild that knows the member wins; avatar is identity-level not guild-level
                }
            } catch (Exception e) {
                log.debug("Failed to refresh cache for snowflake {} via guild {}: {}", snowflake, guildId, e.getMessage());
            }
        }
    }

    if (!toRefresh.isEmpty()) {
        log.debug("Refreshed {} user cache entries", toRefresh.size());
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd backend && ./gradlew compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/DiscordUserCacheService.java
git commit -m "refactor(discord): refresh user cache across all active guilds"
```

---

### Task 14: `AvatarController` — fall back via shared guilds

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/AvatarController.java`

- [ ] **Step 1: Replace the `@GetMapping` body's fallback section**

Replace the fallback (the block currently using `discordConfiguration.getGuildId()` to look up a member, around lines 62–86) with a loop over the guilds the viewer and target user share. Inject `DiscordUserCacheRepository` for the shared-guild list, then iterate:

```java
// Fallback: try each guild the viewer & target share, pick the first that knows the member
List<Long> sharedGuilds = cacheRepository.findGuildIdsBySnowflake(snowflake);
try {
    DiscordService discordService = discordServiceProvider.getIfAvailable();
    if (discordService != null) {
        for (Long guildId : sharedGuilds) {
            Member member = discordService.getMemberFromServer(guildId, Long.parseLong(snowflake));
            if (member != null) {
                String avatarUrl = member.getEffectiveAvatar().getUrl(256);
                cacheService.upsertUser(
                        snowflake,
                        DiscordUtil.getUserDisplayName(member),
                        member.getUser().getName(),
                        avatarUrl,
                        guildId);
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(avatarUrl))
                        .build();
            }
        }
    }
} catch (Exception e) {
    // Ignore and fall back to 404
}
return ResponseEntity.notFound().build();
```

Drop the `private final DiscordConfiguration discordConfiguration;` field and its import.

The `cacheRepository.findGuildIdsBySnowflake` method should already exist (it backs `GuildMembershipService.getGuildIdsForUser`). If not, add it; the existing repository code reads from `discord_user_guild`.

- [ ] **Step 2: Compile**

```bash
cd backend && ./gradlew compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/AvatarController.java
git commit -m "refactor(avatar): use shared guilds instead of yaml guild for fallback"
```

---

### Task 15: `GuildController` — drop yaml fallback

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/GuildController.java`

- [ ] **Step 1: Remove the fallback branch**

The existing `getGuilds`:
```java
List<Long> userGuildIds = guildMembershipService.getGuildIdsForUser(snowflake);
if (userGuildIds.isEmpty()) {
    Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
    return guild != null ? List.of(toDto(guild)) : List.of();
}
return userGuildIds.stream()
        .map(jda::getGuildById)
        .filter(Objects::nonNull)
        .map(this::toDto)
        .toList();
```
becomes:
```java
List<Long> userGuildIds = guildMembershipService.getGuildIdsForUser(snowflake);
return userGuildIds.stream()
        .map(jda::getGuildById)
        .filter(Objects::nonNull)
        .map(this::toDto)
        .toList();
```

Remove `private final DiscordConfiguration discordConfiguration;` and its import.

- [ ] **Step 2: Compile**

```bash
cd backend && ./gradlew compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/GuildController.java
git commit -m "feat(guild): empty list when user shares no guilds with bot"
```

---

### Task 16: Delete `DiscordConfiguration.guildId` (and singleton-guild fields)

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/DiscordConfiguration.java`
- Modify: `backend/src/main/resources/application.yaml` (drop yaml entries)

- [ ] **Step 1: Verify no remaining usage**

```bash
cd backend && grep -rn "discordConfiguration\.\(getGuildId\|getEventsRole\|getAdminRole\|getSeperatorChannel\|getEmoji\)" src/main src/test 2>&1 | head -20
```
Expected: no results. If any remain, fix them before proceeding (this is the grep that catches stragglers from earlier tasks).

- [ ] **Step 2: Replace `DiscordConfiguration`**

```java
package dev.tylercash.event.discord;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.discord")
public class DiscordConfiguration {
    public static final String EVENT_CATEGORY = "outings";
    public static final String EVENT_ARCHIVE_CATEGORY = EVENT_CATEGORY + "-archive";
    private String token;
    private long timeout;
}
```

- [ ] **Step 3: Drop yaml entries**

In `backend/src/main/resources/application.yaml`, remove these lines under `dev.tylercash.discord:`:
```yaml
    seperator-channel: organising
    admin-role: event-admin
```
(`timeout` stays.) The local `application-local.yaml` `guild-id` line is gitignored — note in CLAUDE.md (Task 23) that it's no longer required for the events functionality but is needed under `dev.tylercash.contract.guild-id` for contracts.

- [ ] **Step 4: Build + run all tests**

```bash
cd backend && ./gradlew test 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL. If `application-local.yaml` references the removed `discord.guild-id`, Spring Boot relaxed binding will silently ignore it — no failure expected.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/DiscordConfiguration.java backend/src/main/resources/application.yaml
git commit -m "refactor(config): remove singleton-guild fields from DiscordConfiguration"
```

---

## Phase 5 — Settings API extension

### Task 17: Extend `GuildSettingsDto`, `GuildSettingsRequest`, and the controller

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsDto.java`
- Modify: `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsRequest.java`
- Modify: `backend/src/main/java/dev/tylercash/event/discord/GuildSettingsController.java`

- [ ] **Step 1: Extend the DTOs**

`GuildSettingsDto.java`:
```java
package dev.tylercash.event.discord;

public record GuildSettingsDto(
        String primaryLocationPlaceId,
        String primaryLocationName,
        Double primaryLocationLat,
        Double primaryLocationLng,
        String eventsRole,
        String adminRole,
        String separatorChannel,
        String emojiAccepted,
        String emojiDeclined,
        String emojiMaybe) {

    public static GuildSettingsDto from(Guild row) {
        return new GuildSettingsDto(
                row.getPrimaryLocationPlaceId(),
                row.getPrimaryLocationName(),
                row.getPrimaryLocationLat(),
                row.getPrimaryLocationLng(),
                row.getEventsRole(),
                row.getAdminRole(),
                row.getSeparatorChannel(),
                row.getEmojiAccepted(),
                row.getEmojiDeclined(),
                row.getEmojiMaybe());
    }
}
```

`GuildSettingsRequest.java`:
```java
package dev.tylercash.event.discord;

public record GuildSettingsRequest(
        String primaryLocationPlaceId,
        String primaryLocationName,
        Double primaryLocationLat,
        Double primaryLocationLng,
        String eventsRole,
        String adminRole,
        String separatorChannel,
        String emojiAccepted,
        String emojiDeclined,
        String emojiMaybe) {}
```

- [ ] **Step 2: Update the controller**

```java
@GetMapping
public GuildSettingsDto getSettings(@PathVariable long guildId, @AuthenticationPrincipal OAuth2User principal) {
    String snowflake = principal.getAttribute("id");
    guildMembershipService.assertMember(snowflake, guildId);
    Guild row = guildRepository.findById(guildId).orElse(Guild.withDefaults(guildId));
    return GuildSettingsDto.from(row);
}

@PatchMapping
public GuildSettingsDto updateSettings(
        @PathVariable long guildId,
        @RequestBody GuildSettingsRequest request,
        @AuthenticationPrincipal OAuth2User principal) {
    String snowflake = principal.getAttribute("id");
    guildMembershipService.assertMember(snowflake, guildId);
    long userId = Long.parseLong(snowflake);
    boolean isAdmin = discordService.isUserAdminOfServer(guildId, userId)
            || discordAuthService.isGuildOwner(guildId, userId);
    if (!isAdmin) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role or guild owner required");
    }
    Guild row = guildRepository.findById(guildId).orElseGet(() -> Guild.withDefaults(guildId));

    if (request.primaryLocationPlaceId() != null || request.primaryLocationName() != null
            || request.primaryLocationLat() != null || request.primaryLocationLng() != null) {
        row.setPrimaryLocationPlaceId(request.primaryLocationPlaceId());
        row.setPrimaryLocationName(request.primaryLocationName());
        row.setPrimaryLocationLat(request.primaryLocationLat());
        row.setPrimaryLocationLng(request.primaryLocationLng());
    }
    if (request.eventsRole() != null && !request.eventsRole().isBlank()) row.setEventsRole(request.eventsRole());
    if (request.adminRole() != null && !request.adminRole().isBlank()) row.setAdminRole(request.adminRole());
    row.setSeparatorChannel(request.separatorChannel());  // null = none
    if (request.emojiAccepted() != null && !request.emojiAccepted().isBlank()) row.setEmojiAccepted(request.emojiAccepted());
    if (request.emojiDeclined() != null && !request.emojiDeclined().isBlank()) row.setEmojiDeclined(request.emojiDeclined());
    if (request.emojiMaybe() != null && !request.emojiMaybe().isBlank()) row.setEmojiMaybe(request.emojiMaybe());

    guildRepository.save(row);

    // Re-resolve emoji (and other in-memory caches) immediately so changes take effect without restart.
    net.dv8tion.jda.api.entities.Guild jdaGuild = jda.getGuildById(guildId);
    if (jdaGuild != null) {
        guildEmojiResolver.resolve(jdaGuild, row);
    }
    return GuildSettingsDto.from(row);
}
```

Inject `DiscordAuthService discordAuthService`, `JDA jda`, and `GuildEmojiResolver guildEmojiResolver` (Lombok constructor adds them automatically).

- [ ] **Step 3: Compile**

```bash
cd backend && ./gradlew compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/GuildSettings*.java
git commit -m "feat(api): expose per-guild role/separator/emoji on guild settings"
```

---

### Task 18: Owner-bypass already wired in Task 17 — verify

- [ ] **Step 1: Confirm `discordAuthService.isGuildOwner(guildId, userId)` is called in `GuildSettingsController.updateSettings`**

```bash
grep -n isGuildOwner backend/src/main/java/dev/tylercash/event/discord/GuildSettingsController.java
```
Expected: one match. (No commit — Task 17 already covered this.)

---

## Phase 6 — Frontend

### Task 19: Update `GuildSettingsDto` and `UserInfoDto` types

**Files:**
- Modify: `frontend/src/lib/types.ts`

- [ ] **Step 1: Edit the DTO types**

Replace the existing `GuildSettingsDto`:
```typescript
export type GuildSettingsDto = {
  primaryLocationPlaceId: string | null;
  primaryLocationName: string | null;
  primaryLocationLat: number | null;
  primaryLocationLng: number | null;
  eventsRole: string;
  adminRole: string;
  separatorChannel: string | null;
  emojiAccepted: string;
  emojiDeclined: string;
  emojiMaybe: string;
};
```

Find the `UserInfo` type (search for `admin: boolean` in `types.ts`). Replace `admin: boolean` with `adminGuildIds: number[]`. Note Discord snowflakes exceed `Number.MAX_SAFE_INTEGER` — backend will serialize as JSON numbers but the frontend should keep them as strings to be safe. Use:
```typescript
adminGuildIds: string[];
```
and have the backend JSON-serialize Long via Jackson's `JsonFormat` shape STRING — easier still: change `SecurityController` (Task 12) to map to `List<String>` of `Long.toString(...)`. **Update Task 12 accordingly:**

Add a small backend tweak: in `SecurityController`, change `.toList()` of `Long`s to `.map(String::valueOf).toList()` and update `UserInfoDto.adminGuildIds` to `List<String>`. (If you've already committed Task 12, do this as an amend or a follow-up commit before starting Task 19.)

- [ ] **Step 2: Run typecheck**

```bash
cd frontend && npm run typecheck 2>&1 | tail -20
```
Expected: errors in any consumer of `user.admin`. Fix them in Task 21.

- [ ] **Step 3: Commit (after Task 21 lands)**

```bash
git add frontend/src/lib/types.ts
git commit -m "feat(frontend): extend GuildSettingsDto, switch user.admin to adminGuildIds"
```

---

### Task 20: Update `GuildSettingsForm` for new fields

**Files:**
- Modify: `frontend/src/components/guild/GuildSettingsForm.tsx`

- [ ] **Step 1: Add state + form fields**

In the component, add new state hooks alongside the location ones:
```tsx
const [eventsRole, setEventsRole] = useState("events");
const [adminRole, setAdminRole] = useState("event-admin");
const [separatorChannel, setSeparatorChannel] = useState("");
const [emojiAccepted, setEmojiAccepted] = useState("✅");
const [emojiDeclined, setEmojiDeclined] = useState("❌");
const [emojiMaybe, setEmojiMaybe] = useState("❓");
const [confirmAdminRoleChange, setConfirmAdminRoleChange] = useState(false);
const initialAdminRole = useRef<string | null>(null);
```
Add `import { useRef } from "react";` (or merge with existing `useState`/`useEffect`).

In the `useEffect` that populates from loaded settings, also set:
```tsx
setEventsRole(settings.eventsRole ?? "events");
setAdminRole(settings.adminRole ?? "event-admin");
setSeparatorChannel(settings.separatorChannel ?? "");
setEmojiAccepted(settings.emojiAccepted ?? "✅");
setEmojiDeclined(settings.emojiDeclined ?? "❌");
setEmojiMaybe(settings.emojiMaybe ?? "❓");
initialAdminRole.current = settings.adminRole ?? "event-admin";
```

In `onSubmit`, before sending, check if `adminRole !== initialAdminRole.current && !confirmAdminRoleChange`:
```tsx
if (adminRole !== initialAdminRole.current && !confirmAdminRoleChange) {
  const ok = window.confirm(
    `After this change, only members with the role "${adminRole}" will be able to edit guild settings. Continue?`
  );
  if (!ok) return;
  setConfirmAdminRoleChange(true);
}
```

Update the `updateGuildSettings` payload to include the new fields:
```tsx
await updateGuildSettings(guildId, {
  primaryLocationPlaceId,
  primaryLocationName: primaryLocation.trim() || null,
  primaryLocationLat,
  primaryLocationLng,
  eventsRole,
  adminRole,
  separatorChannel: separatorChannel.trim() || null,
  emojiAccepted,
  emojiDeclined,
  emojiMaybe,
});
```

Add a new `<Slab>` block after the location one with `<Field>`s for each new value. Use the existing `<Field>` helper at the bottom of the file. Example:
```tsx
<Slab className="p-5 flex flex-col gap-4">
  <Field label="events role">
    <input className="w-full px-3 py-2 rounded-input border-[1.5px] border-ink"
           value={eventsRole} onChange={e => setEventsRole(e.target.value)} />
    <p className="text-[11.5px] text-mute font-semibold mt-1">Role pinged when new events are created.</p>
  </Field>
  <Field label="admin role">
    <input className="w-full px-3 py-2 rounded-input border-[1.5px] border-ink"
           value={adminRole} onChange={e => setAdminRole(e.target.value)} />
    <p className="text-[11.5px] text-mute font-semibold mt-1">Members with this role can edit guild settings.</p>
  </Field>
  <Field label="separator channel">
    <input className="w-full px-3 py-2 rounded-input border-[1.5px] border-ink"
           value={separatorChannel} onChange={e => setSeparatorChannel(e.target.value)}
           placeholder="(none)" />
  </Field>
  <Field label="accepted emoji">
    <input className="w-full px-3 py-2 rounded-input border-[1.5px] border-ink"
           value={emojiAccepted} onChange={e => setEmojiAccepted(e.target.value)} />
    <p className="text-[11.5px] text-mute font-semibold mt-1">Unicode emoji or the name of a custom emoji in this guild.</p>
  </Field>
  <Field label="declined emoji">
    <input className="w-full px-3 py-2 rounded-input border-[1.5px] border-ink"
           value={emojiDeclined} onChange={e => setEmojiDeclined(e.target.value)} />
  </Field>
  <Field label="maybe emoji">
    <input className="w-full px-3 py-2 rounded-input border-[1.5px] border-ink"
           value={emojiMaybe} onChange={e => setEmojiMaybe(e.target.value)} />
  </Field>
</Slab>
```

- [ ] **Step 2: Update non-admin redirect**

Replace `if (user && !user.admin) router.push("/")` with:
```tsx
if (user && !user.adminGuildIds.includes(guildId)) router.push("/");
```

- [ ] **Step 3: Run typecheck + tests**

```bash
cd frontend && npm run typecheck 2>&1 | tail -10
cd frontend && npm run test -- --run GuildSettingsForm 2>&1 | tail -20
```
Expected: typecheck clean. The existing `GuildSettingsForm.test.tsx` may need adjustment for new fields — see Task 22.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/guild/GuildSettingsForm.tsx
git commit -m "feat(frontend): edit per-guild role/separator/emoji from settings page"
```

---

### Task 21: Empty-state install CTA in event feed

**Files:**
- Modify: `frontend/src/app/page.tsx` (or whichever file renders the event feed home page — verify path first)
- Modify: `frontend/src/lib/hooks.ts` (drop top-level `admin` usages, replace with per-guild check)

- [ ] **Step 1: Locate the event feed**

```bash
grep -rln "useGuilds\|GuildList\|event feed" frontend/src/app frontend/src/components | head
```

The empty-state branch lives wherever `useGuilds()` is consumed and `[]` is rendered. Check `frontend/src/app/page.tsx` first. If `useGuilds` returns `[]`, render the install CTA in place of the event feed.

- [ ] **Step 2: Add the empty-state component inline**

```tsx
import Link from "next/link";

// inside the component:
if (guildsLoaded && guilds && guilds.length === 0) {
  const installUrl = process.env.NEXT_PUBLIC_BOT_INSTALL_URL || "/login";
  return (
    <div className="mx-auto max-w-[640px] px-4 py-16 text-center">
      <h1 className="text-[28px] font-extrabold tracking-[-0.03em] mb-3">
        Add Peep Bot to your server
      </h1>
      <p className="text-mute mb-6">
        You're logged in, but Peep Bot isn't in any of your Discord servers yet.
        Add it to start managing events.
      </p>
      <Link href={installUrl} className="inline-flex items-center gap-2 px-5 py-3 rounded-chip border-[1.5px] border-ink shadow-rest bg-paper2 font-semibold">
        Add to Discord
      </Link>
    </div>
  );
}
```

Add `NEXT_PUBLIC_BOT_INSTALL_URL` to `frontend/.env.example` (or the equivalent env file). Local dev / mock mode can leave it unset; the fallback is `/login`.

- [ ] **Step 3: Update consumers of `user.admin`**

Run:
```bash
grep -rn "user\.admin\b" frontend/src
```
For each match: replace with `user.adminGuildIds.includes(activeGuildId)` where the guild context is available, or `user.adminGuildIds.length > 0` for "is admin somewhere" checks.

- [ ] **Step 4: Run typecheck + lint + tests**

```bash
cd frontend && npm run typecheck 2>&1 | tail -10
cd frontend && npm run lint 2>&1 | tail -10
cd frontend && npm run test 2>&1 | tail -20
```
Expected: clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/page.tsx frontend/src/lib/hooks.ts frontend/.env.example
git commit -m "feat(frontend): install CTA for users with no shared guilds"
```

---

### Task 22: Update MSW handlers + Vitest for `GuildSettingsForm`

**Files:**
- Modify: MSW handler file under `frontend/src/mocks/` that mocks `/auth/is-logged-in` and `/guild/{id}/settings`
- Modify: `frontend/src/__tests__/components/GuildSettingsForm.test.tsx`

- [ ] **Step 1: Locate and update MSW handlers**

```bash
grep -rln "is-logged-in\|guild/.*settings" frontend/src/mocks
```

In the `is-logged-in` handler, replace the `admin` field with `adminGuildIds: ["<mock-guild-id>"]`. In the `/guild/{guildId}/settings` handler, return the new fields:
```ts
{
  primaryLocationPlaceId: null,
  primaryLocationName: null,
  primaryLocationLat: null,
  primaryLocationLng: null,
  eventsRole: "events",
  adminRole: "event-admin",
  separatorChannel: null,
  emojiAccepted: "✅",
  emojiDeclined: "❌",
  emojiMaybe: "❓",
}
```
And accept the same shape on PATCH.

- [ ] **Step 2: Update the form test to include the new fields**

Update `frontend/src/__tests__/components/GuildSettingsForm.test.tsx` so the mocked settings response includes the new fields (no behavioural change otherwise). If a test asserts the PATCH body, expand its expectations.

- [ ] **Step 3: Run tests**

```bash
cd frontend && npm run test 2>&1 | tail -20
cd frontend && npm run test:e2e 2>&1 | tail -20
```
Expected: clean (e2e may need a guildless-user variant — add one if missing for the install-CTA flow).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/mocks frontend/src/__tests__
git commit -m "test(frontend): MSW + Vitest updates for multi-guild settings"
```

---

## Phase 7 — Integration tests + docs

### Task 23: Update CLAUDE.md and yaml documentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Edit CLAUDE.md**

In the `application-local.yaml` example block, replace:
```yaml
dev.tylercash:
  discord:
    token: "<discord-bot-token>"
    guild-id: <discord-guild-id>
```
with:
```yaml
dev.tylercash:
  discord:
    token: "<discord-bot-token>"
  contract:
    guild-id: <discord-guild-id>  # required only if you use contracts
```

In the "Key Configuration Properties" table, remove the `dev.tylercash.discord.guild-id` row and add `dev.tylercash.contract.guild-id` (purpose: "Discord server contracts run in").

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update for multi-guild config (drop discord.guild-id)"
```

---

### Task 24: Integration test — startup with two guilds

**Files:**
- Create: `backend/src/test/java/dev/tylercash/event/discord/MultiGuildStartupIT.java`

- [ ] **Step 1: Write the integration test**

```java
package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import java.util.List;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MultiGuildStartupIT {

    @MockBean
    private JDA jda;

    @Autowired
    private GuildRepository guildRepository;

    @Autowired
    private GuildRegistrationService guildRegistrationService;

    @Test
    void onboardingTwoGuildsCreatesTwoRows() {
        net.dv8tion.jda.api.entities.Guild g1 = mock(net.dv8tion.jda.api.entities.Guild.class);
        when(g1.getIdLong()).thenReturn(1L);
        when(g1.getName()).thenReturn("Alpha");
        when(g1.getEmojisByName(anyString(), eq(true))).thenReturn(List.of());

        net.dv8tion.jda.api.entities.Guild g2 = mock(net.dv8tion.jda.api.entities.Guild.class);
        when(g2.getIdLong()).thenReturn(2L);
        when(g2.getName()).thenReturn("Beta");
        when(g2.getEmojisByName(anyString(), eq(true))).thenReturn(List.of());

        when(jda.getGuilds()).thenReturn(List.of(g1, g2));

        guildRegistrationService.onboard(g1);
        guildRegistrationService.onboard(g2);

        assertThat(guildRepository.findAllByActiveTrue())
                .extracting(Guild::getGuildId)
                .containsExactlyInAnyOrder(1L, 2L);
    }
}
```

This test depends on `DiscordInitializationService.initialise()` which itself calls `discordChannelService.getOrCreateCategory(...)` — that will likely NPE on the mocked JDA guild. Mock the chain enough to keep it from throwing, OR make `DiscordInitializationService.initialise()` swallow exceptions per-guild and log. Easier: make `GuildRegistrationService.onboard()` wrap the `discordInitializationService.initialise(...)` call in a try/catch that logs and continues.

Decision: update Task 7's `onboard` body to:
```java
try {
    discordInitializationService.initialise(jdaGuild, row);
} catch (Exception e) {
    log.error("Init failed for guild {}: {}", id, e.getMessage(), e);
}
```
This makes startup resilient to any single guild's init failure (e.g., transient JDA issue) — also makes this integration test viable. Add this change as a follow-up commit if Task 7 has already been committed without it.

- [ ] **Step 2: Run the test**

```bash
cd backend && ./gradlew test --tests MultiGuildStartupIT 2>&1 | tail -20
```
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/dev/tylercash/event/discord/MultiGuildStartupIT.java backend/src/main/java/dev/tylercash/event/discord/GuildRegistrationService.java
git commit -m "test(discord): integration test for multi-guild startup onboarding"
```

---

### Task 25: Final smoke

- [ ] **Step 1: Full backend test suite**

```bash
cd backend && ./gradlew test 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full frontend suite**

```bash
cd frontend && npm run lint && npm run typecheck && npm run test && npm run test:e2e 2>&1 | tail -30
```
Expected: all green.

- [ ] **Step 3: Spotless**

```bash
cd backend && ./gradlew spotlessCheck 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL. If not, run `./gradlew spotlessApply` and commit.

- [ ] **Step 4: Manual smoke (note for the executor)**

Bring up the local stack (`docker-compose up -d`, `./gradlew bootRun`, `npm run dev`) and verify:
1. Login as a user who is in the configured Discord guild → event feed renders.
2. (If you can test it) login as a user who is in zero guilds the bot is in → install CTA renders.
3. Edit settings → set a different `events_role`; create an event; new event message pings the new role.
4. Bot joins a fresh test guild → row appears in `guild` table; categories `outings`/`outings-archive` get created.

---

## Notes for executor

- **Task ordering matters.** Tasks 7 and 8 are interdependent (compile graph). Land Task 8 first to define `DiscordInitializationService.initialise(jdaGuild, row)`, then Task 7 which calls it. Task 12's owner-bypass tweak (`adminGuildIds: List<String>`) needs to be reconciled with Task 19's frontend type — see the note in Task 19 Step 1.
- **Emoji resolution change of behaviour:** the previous implementation also rewrote `ContractConfiguration.Emoji` via reflection. This plan drops contract emoji custom-resolution. If you want to keep it, add a one-off `resolveContractEmoji(jdaGuild)` call inside `DiscordInitializationService.initialise(...)` guarded by `contractGuildResolver.isContractsGuild(jdaGuild.getIdLong())`.
- **Long → JSON serialization for `adminGuildIds`:** prefer `List<String>` over `List<Long>` so JS doesn't truncate snowflakes.
- **Liquibase H2/Postgres parity:** the `defaultValueComputed: NOW()` in Task 1 works on both. If H2 in tests rejects it, swap to `defaultValueDate: now()` or use a `@PrePersist` on the entity.

## Cleanup

When this branch merges:
- Local developers must add `dev.tylercash.contract.guild-id: <id>` to `application-local.yaml` if they use contracts; remove the now-unused `dev.tylercash.discord.guild-id` line.
- Run `./gradlew :backend:bootRun` once on prod-like data to verify the Liquibase changeset applies cleanly.
