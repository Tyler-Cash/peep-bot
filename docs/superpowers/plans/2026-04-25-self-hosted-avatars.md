# Self-Hosted Discord Avatars Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Download Discord avatars on login/cache-refresh, store bytes in the DB, serve them from `/api/avatar/{snowflake}`, and remove the `cdn.discordapp.com` external dependency.

**Architecture:** Add `avatar_bytes` (BYTEA, nullable) and `avatar_content_type` (TEXT, nullable) to `discord_user_cache`. A new `AvatarDownloadService` fetches raw bytes from Discord CDN and is called from two existing paths: OAuth2 login (`CustomOAuth2UserService`) and the JDA scheduled cache refresh (`DiscordUserCacheService.refreshStaleEntries`). A new `AvatarController` serves the stored bytes. All response DTOs that currently carry `null` for `avatarUrl` are updated to return `/api/avatar/{snowflake}` instead. The frontend `Avatar` component gains an `onError` fallback so a broken/missing avatar gracefully degrades to initials.

**Tech Stack:** Spring Boot 3.5.8 / Java 21, Liquibase, JPA/Hibernate, Spring `RestClient` (built into spring-web), JUnit 5 / Mockito, React 19 / TypeScript

---

## File Map

| Action   | Path |
|----------|------|
| Modify   | `backend/src/main/resources/db/changelog/db.changelog-master.yaml` |
| Modify   | `backend/src/main/java/dev/tylercash/event/discord/model/DiscordUserCache.java` |
| Create   | `backend/src/main/java/dev/tylercash/event/discord/AvatarDownloadService.java` |
| Create   | `backend/src/test/java/dev/tylercash/event/discord/AvatarDownloadServiceTest.java` |
| Modify   | `backend/src/main/java/dev/tylercash/event/discord/DiscordUserCacheService.java` |
| Modify   | `backend/src/main/java/dev/tylercash/event/security/oauth2/CustomOAuth2UserService.java` |
| Create   | `backend/src/main/java/dev/tylercash/event/discord/AvatarController.java` |
| Create   | `backend/src/test/java/dev/tylercash/event/discord/AvatarControllerTest.java` |
| Modify   | `backend/src/main/java/dev/tylercash/event/security/UserInfoDto.java` |
| Modify   | `backend/src/test/java/dev/tylercash/event/global/SecurityControllerTest.java` |
| Modify   | `backend/src/main/java/dev/tylercash/event/event/model/AttendeeDto.java` |
| Modify   | `backend/src/main/java/dev/tylercash/event/event/model/EventDto.java` |
| Modify   | `backend/src/main/java/dev/tylercash/event/event/model/EventDetailDto.java` |
| Modify   | `backend/src/main/java/dev/tylercash/event/event/EventController.java` |
| Modify   | `backend/src/main/java/dev/tylercash/event/rewind/model/AttendeeStatDto.java` |
| Modify   | `backend/src/main/java/dev/tylercash/event/rewind/RewindService.java` |
| Modify   | `frontend/src/components/ui/Avatar.tsx` |
| Modify   | `frontend/next.config.ts` |

---

## Task 1: DB migration — add avatar columns to discord_user_cache

**Files:**
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`
- Test: `backend/src/test/java/dev/tylercash/event/db/LiquibaseMigrationTest.java` (existing, will run automatically)

- [ ] **Step 1: Add migration changeSet at the bottom of db.changelog-master.yaml**

Append before the final `- include:` line (i.e., before the contract changelog include):

```yaml
  - changeSet:
      id: add avatar columns to discord_user_cache
      author: tyler
      changes:
        - addColumn:
            tableName: discord_user_cache
            columns:
              - column:
                  name: avatar_bytes
                  type: bytea
              - column:
                  name: avatar_content_type
                  type: text
```

- [ ] **Step 2: Run the existing Liquibase migration test to confirm it applies cleanly**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.db.LiquibaseMigrationTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, tests pass.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(db): add avatar_bytes and avatar_content_type columns to discord_user_cache"
```

---

## Task 2: Update DiscordUserCache entity

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/model/DiscordUserCache.java`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/dev/tylercash/event/discord/DiscordUserCacheTest.java`:

```java
package dev.tylercash.event.discord;

import dev.tylercash.event.discord.model.DiscordUserCache;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class DiscordUserCacheTest {

    @Test
    void avatarFieldsDefaultToNull() {
        DiscordUserCache cache = new DiscordUserCache("123", "TestUser", Instant.now(), null, null);
        assertThat(cache.getAvatarBytes()).isNull();
        assertThat(cache.getAvatarContentType()).isNull();
    }

    @Test
    void avatarFieldsCanBeSet() {
        byte[] bytes = new byte[]{1, 2, 3};
        DiscordUserCache cache = new DiscordUserCache("123", "TestUser", Instant.now(), bytes, "image/webp");
        assertThat(cache.getAvatarBytes()).isEqualTo(bytes);
        assertThat(cache.getAvatarContentType()).isEqualTo("image/webp");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.discord.DiscordUserCacheTest" 2>&1 | tail -20
```

Expected: FAIL — constructor not found / fields not present.

- [ ] **Step 3: Update DiscordUserCache.java**

Replace the entire file:

```java
package dev.tylercash.event.discord.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "discord_user_cache")
@NoArgsConstructor
@AllArgsConstructor
public class DiscordUserCache {
    @Id
    private String snowflake;

    private String displayName;
    private Instant updatedAt;

    @Lob
    @Column(columnDefinition = "bytea")
    private byte[] avatarBytes;

    private String avatarContentType;
}
```

- [ ] **Step 4: Run the test to confirm it passes**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.discord.DiscordUserCacheTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the full test suite to check for regressions**

```bash
cd backend && ./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. If `DiscordUserCacheService` tests fail because `upsertUser` still calls the 3-arg constructor, fix those callers — `upsertUser` will be updated in Task 4.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/model/DiscordUserCache.java \
        backend/src/test/java/dev/tylercash/event/discord/DiscordUserCacheTest.java
git commit -m "feat(discord): add avatarBytes and avatarContentType fields to DiscordUserCache"
```

---

## Task 3: AvatarDownloadService

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/AvatarDownloadService.java`
- Create: `backend/src/test/java/dev/tylercash/event/discord/AvatarDownloadServiceTest.java`

The service fetches avatar bytes from a URL and returns `Optional<AvatarBytes>`. A `null` or blank URL returns empty. HTTP errors return empty (logged at debug). The record `AvatarBytes(byte[] bytes, String contentType)` holds the result.

- [ ] **Step 1: Write the failing test**

```java
package dev.tylercash.event.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AvatarDownloadServiceTest {

    @Test
    void download_returnsEmpty_whenUrlIsNull() {
        AvatarDownloadService service = new AvatarDownloadService();
        assertThat(service.download(null)).isEmpty();
    }

    @Test
    void download_returnsEmpty_whenUrlIsBlank() {
        AvatarDownloadService service = new AvatarDownloadService();
        assertThat(service.download("  ")).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.discord.AvatarDownloadServiceTest" 2>&1 | tail -20
```

Expected: FAIL — class not found.

- [ ] **Step 3: Create AvatarDownloadService.java**

```java
package dev.tylercash.event.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AvatarDownloadService {

    public record AvatarBytes(byte[] bytes, String contentType) {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public Optional<AvatarBytes> download(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.debug("Avatar download returned {} for {}", response.statusCode(), url);
                return Optional.empty();
            }
            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .map(ct -> ct.split(";")[0].trim())
                    .orElse("image/webp");
            return Optional.of(new AvatarBytes(response.body(), contentType));
        } catch (Exception e) {
            log.debug("Avatar download failed for {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    /** Build the Discord CDN URL for a user avatar hash. Returns null if hash is null. */
    public static String discordAvatarUrl(String snowflake, String avatarHash) {
        if (avatarHash == null || avatarHash.isBlank()) {
            return null;
        }
        String ext = avatarHash.startsWith("a_") ? "gif" : "webp";
        return "https://cdn.discordapp.com/avatars/" + snowflake + "/" + avatarHash + "." + ext + "?size=256";
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.discord.AvatarDownloadServiceTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/AvatarDownloadService.java \
        backend/src/test/java/dev/tylercash/event/discord/AvatarDownloadServiceTest.java
git commit -m "feat(discord): add AvatarDownloadService to fetch Discord avatar bytes"
```

---

## Task 4: Download avatar on cache upsert and scheduled refresh

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/DiscordUserCacheService.java`

`upsertUser` gains a `String avatarUrl` parameter. `refreshStaleEntries` passes `member.getEffectiveAvatarUrl()`. The existing callers of `upsertUser(snowflake, displayName)` must be updated to the 3-arg form.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/dev/tylercash/event/discord/DiscordUserCacheServiceTest.java`:

```java
package dev.tylercash.event.discord;

import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.model.DiscordUserCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiscordUserCacheServiceTest {

    @Mock DiscordUserCacheRepository cacheRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock EventRepository eventRepository;
    @Mock ObjectProvider<DiscordService> discordServiceProvider;
    @Mock DiscordConfiguration discordConfiguration;
    @Mock AvatarDownloadService avatarDownloadService;

    @Test
    void upsertUser_downloadsAndStoresAvatar_whenUrlProvided() {
        byte[] fakeBytes = new byte[]{1, 2, 3};
        when(avatarDownloadService.download("https://cdn.discordapp.com/avatars/123/hash.webp"))
                .thenReturn(Optional.of(new AvatarDownloadService.AvatarBytes(fakeBytes, "image/webp")));

        DiscordUserCacheService service = new DiscordUserCacheService(
                cacheRepository, attendanceRepository, eventRepository,
                discordServiceProvider, discordConfiguration, avatarDownloadService);

        service.upsertUser("123", "TestUser", "https://cdn.discordapp.com/avatars/123/hash.webp");

        ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isEqualTo(fakeBytes);
        assertThat(captor.getValue().getAvatarContentType()).isEqualTo("image/webp");
    }

    @Test
    void upsertUser_savesWithNullAvatar_whenDownloadFails() {
        when(avatarDownloadService.download(any())).thenReturn(Optional.empty());

        DiscordUserCacheService service = new DiscordUserCacheService(
                cacheRepository, attendanceRepository, eventRepository,
                discordServiceProvider, discordConfiguration, avatarDownloadService);

        service.upsertUser("123", "TestUser", "https://cdn.discordapp.com/avatars/123/hash.webp");

        ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isNull();
        assertThat(captor.getValue().getAvatarContentType()).isNull();
    }

    @Test
    void upsertUser_savesWithNullAvatar_whenUrlIsNull() {
        DiscordUserCacheService service = new DiscordUserCacheService(
                cacheRepository, attendanceRepository, eventRepository,
                discordServiceProvider, discordConfiguration, avatarDownloadService);

        service.upsertUser("123", "TestUser", null);

        ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isNull();
        verify(avatarDownloadService, never()).download(any());
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.discord.DiscordUserCacheServiceTest" 2>&1 | tail -20
```

Expected: FAIL — `upsertUser` has wrong signature.

- [ ] **Step 3: Update DiscordUserCacheService.java**

Change the class to:

```java
package dev.tylercash.event.discord;

import static java.util.concurrent.TimeUnit.SECONDS;

import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.AvatarDownloadService.AvatarBytes;
import dev.tylercash.event.discord.model.DiscordUserCache;
import io.micrometer.observation.annotation.Observed;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DiscordUserCacheService {
    private static final int REFRESH_BATCH_SIZE = 10;
    private static final long STALE_MINUTES = 30;

    private final DiscordUserCacheRepository cacheRepository;
    private final AttendanceRepository attendanceRepository;
    private final EventRepository eventRepository;
    private final ObjectProvider<DiscordService> discordServiceProvider;
    private final DiscordConfiguration discordConfiguration;
    private final AvatarDownloadService avatarDownloadService;

    public DiscordUserCacheService(
            DiscordUserCacheRepository cacheRepository,
            AttendanceRepository attendanceRepository,
            EventRepository eventRepository,
            ObjectProvider<DiscordService> discordServiceProvider,
            DiscordConfiguration discordConfiguration,
            AvatarDownloadService avatarDownloadService) {
        this.cacheRepository = cacheRepository;
        this.attendanceRepository = attendanceRepository;
        this.eventRepository = eventRepository;
        this.discordServiceProvider = discordServiceProvider;
        this.discordConfiguration = discordConfiguration;
        this.avatarDownloadService = avatarDownloadService;
    }

    public void upsertUser(String snowflake, String displayName, String avatarUrl) {
        byte[] avatarBytes = null;
        String avatarContentType = null;
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            Optional<AvatarBytes> downloaded = avatarDownloadService.download(avatarUrl);
            if (downloaded.isPresent()) {
                avatarBytes = downloaded.get().bytes();
                avatarContentType = downloaded.get().contentType();
            }
        }
        cacheRepository.save(new DiscordUserCache(snowflake, displayName, Instant.now(), avatarBytes, avatarContentType));
    }

    public String getDisplayName(String snowflake) {
        if (snowflake == null || snowflake.isBlank()) {
            return "Unknown User";
        }
        return cacheRepository
                .findById(snowflake)
                .map(DiscordUserCache::getDisplayName)
                .orElse("Unknown User (#" + snowflake.substring(Math.max(0, snowflake.length() - 4)) + ")");
    }

    public Map<String, String> getDisplayNames(Collection<String> snowflakes) {
        if (snowflakes == null || snowflakes.isEmpty()) {
            return Map.of();
        }
        Set<String> unique =
                snowflakes.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.toSet());
        if (unique.isEmpty()) {
            return Map.of();
        }
        return cacheRepository.findAllBySnowflakeIn(unique).stream()
                .collect(Collectors.toMap(DiscordUserCache::getSnowflake, DiscordUserCache::getDisplayName));
    }

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

        for (String snowflake : toRefresh) {
            try {
                Member member = discordServiceProvider
                        .getObject()
                        .getMemberFromServer(discordConfiguration.getGuildId(), Long.parseLong(snowflake));
                if (member != null) {
                    String displayName = DiscordUtil.getUserDisplayName(member);
                    String avatarUrl = member.getEffectiveAvatarUrl() + "?size=256";
                    upsertUser(snowflake, displayName, avatarUrl);
                }
            } catch (Exception e) {
                log.debug("Failed to refresh cache for snowflake {}: {}", snowflake, e.getMessage());
            }
        }

        if (!toRefresh.isEmpty()) {
            log.debug("Refreshed {} user cache entries", toRefresh.size());
        }
    }
}
```

- [ ] **Step 4: Fix all callers of the old 2-arg upsertUser**

Search for all call sites:
```bash
grep -rn "upsertUser(" /home/tcash/code/peep-bot/backend/src/main/java --include="*.java"
```

Expected callers: `EventController.java` (calls `upsertUser(discordId, displayName)`). Update each to pass `null` as the avatar URL — avatar will be downloaded on the next JDA refresh or login.

In `EventController.java`, change:
```java
discordUserCacheService.upsertUser(discordId, displayName);
```
to:
```java
discordUserCacheService.upsertUser(discordId, displayName, null);
```

- [ ] **Step 5: Run the tests**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.discord.DiscordUserCacheServiceTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run full suite**

```bash
cd backend && ./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/DiscordUserCacheService.java \
        backend/src/main/java/dev/tylercash/event/event/EventController.java \
        backend/src/test/java/dev/tylercash/event/discord/DiscordUserCacheServiceTest.java
git commit -m "feat(discord): download and store avatar bytes in upsertUser and cache refresh"
```

---

## Task 5: Download avatar on OAuth2 login

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/security/oauth2/CustomOAuth2UserService.java`

On successful login, if the OAuth2 user has an `avatar` attribute (hash), construct the CDN URL and call `upsertUser` with it. `DiscordUserCacheService` is injected here.

- [ ] **Step 1: Update CustomOAuth2UserService.java**

```java
package dev.tylercash.event.security.oauth2;

import dev.tylercash.event.discord.AvatarDownloadService;
import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
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
    private DiscordConfiguration discordConfiguration;
    private DiscordUserCacheService discordUserCacheService;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String snowflake = Objects.requireNonNull(oAuth2User.getAttribute("id"));
        String username = oAuth2User.getAttribute("username");
        log.info("Authenticated user {}", username);

        if (!discordService.isUserMemberOfServer(
                discordConfiguration.getGuildId(), Long.parseLong(snowflake))) {
            log.warn("User {} not a member of the server. id: {}", username, snowflake);
            throw new OAuth2AuthenticationException(
                    "User not a member of discord server " + discordConfiguration.getGuildId());
        }

        String avatarHash = oAuth2User.getAttribute("avatar");
        String avatarUrl = AvatarDownloadService.discordAvatarUrl(snowflake, avatarHash);
        discordUserCacheService.upsertUser(snowflake, username != null ? username : snowflake, avatarUrl);

        return oAuth2User;
    }
}
```

- [ ] **Step 2: Run the full test suite**

```bash
cd backend && ./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/security/oauth2/CustomOAuth2UserService.java
git commit -m "feat(auth): download and cache Discord avatar bytes on OAuth2 login"
```

---

## Task 6: AvatarController — serve avatar bytes

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/AvatarController.java`
- Create: `backend/src/test/java/dev/tylercash/event/discord/AvatarControllerTest.java`

`GET /avatar/{snowflake}` — returns stored bytes with correct `Content-Type` and `Cache-Control: public, max-age=86400`. Returns 404 if the user is not cached or has no avatar stored.

- [ ] **Step 1: Write the failing test**

```java
package dev.tylercash.event.discord;

import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.discord.model.DiscordUserCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvatarControllerTest {

    @Test
    void getAvatar_returns200WithBytes_whenAvatarStored() {
        DiscordUserCacheRepository repo = mock(DiscordUserCacheRepository.class);
        byte[] bytes = new byte[]{1, 2, 3};
        DiscordUserCache cached = new DiscordUserCache("123", "User", Instant.now(), bytes, "image/webp");
        when(repo.findById("123")).thenReturn(Optional.of(cached));

        AvatarController controller = new AvatarController(repo);
        ResponseEntity<byte[]> response = controller.getAvatar("123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(bytes);
        assertThat(response.getHeaders().getContentType()).hasToString("image/webp");
        assertThat(response.getHeaders().getCacheControl()).contains("max-age=86400");
    }

    @Test
    void getAvatar_returns404_whenUserNotCached() {
        DiscordUserCacheRepository repo = mock(DiscordUserCacheRepository.class);
        when(repo.findById("999")).thenReturn(Optional.empty());

        AvatarController controller = new AvatarController(repo);
        ResponseEntity<byte[]> response = controller.getAvatar("999");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getAvatar_returns404_whenAvatarBytesNull() {
        DiscordUserCacheRepository repo = mock(DiscordUserCacheRepository.class);
        DiscordUserCache cached = new DiscordUserCache("123", "User", Instant.now(), null, null);
        when(repo.findById("123")).thenReturn(Optional.of(cached));

        AvatarController controller = new AvatarController(repo);
        ResponseEntity<byte[]> response = controller.getAvatar("123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.discord.AvatarControllerTest" 2>&1 | tail -20
```

Expected: FAIL — class not found.

- [ ] **Step 3: Create AvatarController.java**

```java
package dev.tylercash.event.discord;

import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.discord.model.DiscordUserCache;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/avatar")
@RequiredArgsConstructor
public class AvatarController {

    private final DiscordUserCacheRepository cacheRepository;

    @GetMapping("/{snowflake}")
    public ResponseEntity<byte[]> getAvatar(@PathVariable String snowflake) {
        Optional<DiscordUserCache> cached = cacheRepository.findById(snowflake);
        if (cached.isEmpty() || cached.get().getAvatarBytes() == null) {
            return ResponseEntity.notFound().build();
        }
        DiscordUserCache entry = cached.get();
        MediaType mediaType = MediaType.parseMediaType(
                entry.getAvatarContentType() != null ? entry.getAvatarContentType() : "image/webp");
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .body(entry.getAvatarBytes());
    }
}
```

- [ ] **Step 4: Ensure /avatar/** is permitted without authentication**

Check `backend/src/main/java/dev/tylercash/event/security/WebSecurityConfig.java`. Find the `requestMatchers` that allow public paths. Add `/avatar/**` to the permitted list (alongside `/oauth2/**`, `/actuator/**`, etc.).

Locate the existing permit line (it typically looks like):
```java
.requestMatchers("/oauth2/**", "/login/**", "/actuator/**", "/csrf").permitAll()
```

Change it to include `/avatar/**`:
```java
.requestMatchers("/oauth2/**", "/login/**", "/actuator/**", "/csrf", "/avatar/**").permitAll()
```

- [ ] **Step 5: Run the tests**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.discord.AvatarControllerTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/AvatarController.java \
        backend/src/main/java/dev/tylercash/event/security/WebSecurityConfig.java \
        backend/src/test/java/dev/tylercash/event/discord/AvatarControllerTest.java
git commit -m "feat(discord): add GET /avatar/{snowflake} endpoint to serve stored avatar bytes"
```

---

## Task 7: Include avatarUrl in UserInfoDto and SecurityController

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/security/UserInfoDto.java`
- Modify: `backend/src/main/java/dev/tylercash/event/global/SecurityController.java`
- Modify: `backend/src/test/java/dev/tylercash/event/global/SecurityControllerTest.java`

- [ ] **Step 1: Update UserInfoDto.java**

```java
package dev.tylercash.event.security;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserInfoDto {
    private String username;
    private String discordId;
    private boolean admin;
    private String avatarUrl;
}
```

- [ ] **Step 2: Update SecurityController.java**

Change the `isLoggedIn` method return statement:

```java
return new UserInfoDto(username, userSnowflake, isAdmin, "/api/avatar/" + userSnowflake);
```

- [ ] **Step 3: Update SecurityControllerTest.java — add avatarUrl assertions**

In each test that creates a controller and calls `isLoggedIn`, add an assertion after the existing ones:

```java
assertThat(result.getAvatarUrl()).isEqualTo("/api/avatar/" + DISCORD_ID);
```

For the test `isLoggedIn_usesGuildIdFromConfiguration`, no assertion on `avatarUrl` is needed (not the focus there).

- [ ] **Step 4: Run the tests**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.global.SecurityControllerTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run full suite**

```bash
cd backend && ./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. Fix any compilation errors from callers of the old 3-arg `UserInfoDto` constructor.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/security/UserInfoDto.java \
        backend/src/main/java/dev/tylercash/event/global/SecurityController.java \
        backend/src/test/java/dev/tylercash/event/global/SecurityControllerTest.java
git commit -m "feat(auth): include avatarUrl in /auth/is-logged-in response"
```

---

## Task 8: Add avatarUrl to AttendeeDto

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/event/model/AttendeeDto.java`

`avatarUrl` is `/api/avatar/{snowflake}` when `snowflake != null`; `null` for guest (+1) attendees who have no snowflake.

- [ ] **Step 1: Update AttendeeDto.java**

```java
package dev.tylercash.event.event.model;

import java.time.Instant;
import lombok.Data;

@Data
public class AttendeeDto {
    private final String snowflake;
    private final String name;
    private final Instant instant;
    private final String ownerSnowflake;
    private final String avatarUrl;

    public AttendeeDto(Attendee attendee) {
        this.snowflake = attendee.getSnowflake();
        this.name = attendee.getName();
        this.instant = attendee.getInstant();
        this.ownerSnowflake = null;
        this.avatarUrl = attendee.getSnowflake() != null ? "/api/avatar/" + attendee.getSnowflake() : null;
    }

    public AttendeeDto(AttendanceRecord record, String resolvedName) {
        this.snowflake = record.getSnowflake();
        this.name = resolvedName;
        this.instant = record.getRecordedAt();
        this.ownerSnowflake = record.getOwnerSnowflake();
        this.avatarUrl = record.getSnowflake() != null ? "/api/avatar/" + record.getSnowflake() : null;
    }
}
```

- [ ] **Step 2: Run the full test suite**

```bash
cd backend && ./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/event/model/AttendeeDto.java
git commit -m "feat(event): add avatarUrl to AttendeeDto"
```

---

## Task 9: Add host and hostAvatarUrl to EventDto and update EventController

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/event/model/EventDto.java`
- Modify: `backend/src/main/java/dev/tylercash/event/event/EventController.java`

`EventDto` gains `host` (creator display name) and `hostAvatarUrl` (`/api/avatar/{creatorSnowflake}`). The existing no-arg constructor keeps working (host/hostAvatarUrl will be null). A new constructor takes a resolved display name so the list endpoint can populate it.

- [ ] **Step 1: Update EventDto.java**

Add the two new fields and a new constructor:

```java
// after existing fields, add:
private String host;
private String hostAvatarUrl;
```

Change the existing `EventDto(Event event)` constructor to also set `hostAvatarUrl` from the creator snowflake (display name unknown here, so leave `host` null from this constructor path):

```java
public EventDto(Event event) {
    this.id = event.getId();
    this.name = event.getName();
    this.description = event.getDescription();
    this.location = event.getLocation();
    this.capacity = event.getCapacity();
    this.cost = event.getCost();
    this.dateTime = event.getDateTime();
    String creator = event.getCreator();
    this.host = event.getCreatorDisplayName(); // transient, may be null
    this.hostAvatarUrl = (creator != null && !creator.isBlank()) ? "/api/avatar/" + creator : null;
}
```

Add a constructor that accepts the resolved display name:

```java
public EventDto(Event event, String hostDisplayName) {
    this(event);
    this.host = hostDisplayName;
}
```

- [ ] **Step 2: Update EventController.getEvents() to resolve host names**

In `EventController.java`, change the `getEvents` method:

```java
@GetMapping
public Page<EventDto> getEvents(@PageableDefault Pageable pageable) {
    Page<Event> events = eventService.getActiveEvents(pageable);
    Set<String> creatorSnowflakes = events.stream()
            .map(Event::getCreator)
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.toSet());
    Map<String, String> nameMap = discordUserCacheService.getDisplayNames(creatorSnowflakes);
    return events.map(event -> new EventDto(event, nameMap.getOrDefault(event.getCreator(), event.getCreator())));
}
```

- [ ] **Step 3: Run the full test suite**

```bash
cd backend && ./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. Fix any compilation issues (e.g., import `java.util.Set` if missing).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/event/model/EventDto.java \
        backend/src/main/java/dev/tylercash/event/event/EventController.java
git commit -m "feat(event): add host and hostAvatarUrl to EventDto"
```

---

## Task 10: Add host fields to EventDetailDto

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/event/model/EventDetailDto.java`
- Modify: `backend/src/main/java/dev/tylercash/event/event/EventController.java`

`EventDetailDto` extends `EventDto`, so it inherits `host`/`hostAvatarUrl`. We just need the constructor that takes `(event, completed, summary, nameMap)` to resolve the creator's display name from `nameMap`.

- [ ] **Step 1: Update EventDetailDto constructors**

In the `EventDetailDto(Event event, boolean completed, AttendanceSummary summary, Map<String, String> nameMap)` constructor, add after `super(event)`:

```java
String creator = event.getCreator();
if (creator != null && !creator.isBlank()) {
    this.setHost(nameMap.getOrDefault(creator, creator));
    this.setHostAvatarUrl("/api/avatar/" + creator);
}
```

Full updated constructor:

```java
public EventDetailDto(Event event, boolean completed, AttendanceSummary summary, Map<String, String> nameMap) {
    super(event);
    String creator = event.getCreator();
    if (creator != null && !creator.isBlank()) {
        this.setHost(nameMap.getOrDefault(creator, creator));
        this.setHostAvatarUrl("/api/avatar/" + creator);
    }
    this.accepted = toSortedRecordList(summary.accepted(), nameMap);
    this.declined = toSortedRecordList(summary.declined(), nameMap);
    this.maybe = toSortedRecordList(summary.maybe(), nameMap);
    this.completed = completed;
    this.hasPrivateChannel = event.getPrivateChannelId() != null;
}
```

- [ ] **Step 2: Add creator snowflake to the nameMap in EventController.getEvent()**

In `EventController.getEvent()`, the `nameMap` already contains attendee snowflakes. The creator might not be an attendee (e.g., they declined). Add the creator to `allSnowflakes` before building the map:

```java
@GetMapping(path = "/{id}")
public EventDetailDto getEvent(@PathVariable UUID id) {
    Event event = eventService.getEvent(id);
    boolean completed = eventService.isCompleted(event);
    AttendanceSummary summary = attendanceService.getCurrentAttendance(id);

    java.util.Set<String> allSnowflakes = Stream.of(
                    summary.accepted().stream(), summary.declined().stream(), summary.maybe().stream())
            .flatMap(s -> s)
            .map(AttendanceRecord::getSnowflake)
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.toSet());

    // Include creator so EventDetailDto can resolve the host name
    if (event.getCreator() != null && !event.getCreator().isBlank()) {
        allSnowflakes.add(event.getCreator());
    }

    Map<String, String> nameMap = discordUserCacheService.getDisplayNames(allSnowflakes);
    return new EventDetailDto(event, completed, summary, nameMap);
}
```

- [ ] **Step 3: Run the full test suite**

```bash
cd backend && ./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/event/model/EventDetailDto.java \
        backend/src/main/java/dev/tylercash/event/event/EventController.java
git commit -m "feat(event): resolve host name and avatarUrl in EventDetailDto"
```

---

## Task 11: Add avatarUrl to AttendeeStatDto in Rewind

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/rewind/model/AttendeeStatDto.java`
- Modify: `backend/src/main/java/dev/tylercash/event/rewind/RewindService.java`

- [ ] **Step 1: Update AttendeeStatDto.java**

```java
package dev.tylercash.event.rewind.model;

public record AttendeeStatDto(String displayName, int eventCount, String avatarUrl) {}
```

- [ ] **Step 2: Update RewindService.java — pass avatarUrl when building AttendeeStatDto**

For `topAttendees`, change the `.map(r -> ...)` inside `getGuildStats`/`getUserStats`:

```java
// Keep the attendeeSnowflakes set (already exists in the code)
List<AttendeeStatDto> topAttendees = attendeeRows.stream()
        .map(r -> {
            String snowflake = (String) r[0];
            String name = attendeeNames.getOrDefault(snowflake, "Unknown");
            String avatarUrl = "/api/avatar/" + snowflake;
            return new AttendeeStatDto(name, ((Number) r[1]).intValue(), avatarUrl);
        })
        .collect(Collectors.toList());
```

For `topOrganizers`:

```java
List<AttendeeStatDto> topOrganizers = orgRows.stream()
        .map(r -> {
            String raw = (String) r[0];
            String name = orgNames.getOrDefault(raw, raw != null && !raw.isBlank() ? raw : "Unknown");
            String avatarUrl = (raw != null && !raw.isBlank()) ? "/api/avatar/" + raw : null;
            return new AttendeeStatDto(name, ((Number) r[1]).intValue(), avatarUrl);
        })
        .collect(Collectors.toList());
```

- [ ] **Step 3: Run the full test suite**

```bash
cd backend && ./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. Fix any `AttendeeStatDto` constructor call sites — there may be test fixtures using the 2-arg form that now need the 3-arg form.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/rewind/model/AttendeeStatDto.java \
        backend/src/main/java/dev/tylercash/event/rewind/RewindService.java
git commit -m "feat(rewind): add avatarUrl to AttendeeStatDto"
```

---

## Task 12: Frontend — Avatar onError fallback

**Files:**
- Modify: `frontend/src/components/ui/Avatar.tsx`

When the `/api/avatar/{snowflake}` endpoint returns 404 (avatar not yet cached), the `<img>` will fail. Add an `onError` handler that removes the `src` and forces the initials fallback.

- [ ] **Step 1: Update Avatar.tsx**

```tsx
import clsx from "@/lib/clsx";
import { initials } from "@/lib/format";
import { useState } from "react";

export type AvatarRef = {
  name: string;
  hue?: string;
  avatarUrl?: string | null;
};

export function Avatar({
  who,
  size = 32,
  className,
}: {
  who: AvatarRef;
  size?: number;
  className?: string;
}) {
  const [imgFailed, setImgFailed] = useState(false);
  const bg = who.hue ?? "#7BC24F";

  if (who.avatarUrl && !imgFailed) {
    return (
      // eslint-disable-next-line @next/next/no-img-element
      <img
        src={who.avatarUrl}
        alt={who.name}
        width={size}
        height={size}
        className={clsx(
          "rounded-full border-[1.5px] border-ink object-cover",
          className,
        )}
        style={{ width: size, height: size }}
        onError={() => setImgFailed(true)}
      />
    );
  }
  return (
    <span
      className={clsx(
        "inline-flex items-center justify-center rounded-full border-[1.5px] border-ink font-bold text-ink text-[11px] leading-none select-none",
        className,
      )}
      style={{ width: size, height: size, background: bg }}
      aria-label={who.name}
    >
      {initials(who.name)}
    </span>
  );
}
```

- [ ] **Step 2: Run frontend lint and type-check**

```bash
cd frontend && npm run lint && npx tsc --noEmit 2>&1 | tail -20
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/ui/Avatar.tsx
git commit -m "feat(ui): add onError fallback to Avatar component for missing avatars"
```

---

## Task 13: Frontend — Remove cdn.discordapp.com from remotePatterns

**Files:**
- Modify: `frontend/next.config.ts`

- [ ] **Step 1: Update next.config.ts**

```ts
import type { NextConfig } from "next";

const config: NextConfig = {
  reactStrictMode: true,
};

export default config;
```

- [ ] **Step 2: Run the frontend build to confirm no errors**

```bash
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
cd frontend && npm run build 2>&1 | tail -30
```

Expected: build succeeds.

- [ ] **Step 3: Commit**

```bash
git add frontend/next.config.ts
git commit -m "chore(frontend): remove cdn.discordapp.com from remotePatterns"
```

---

## Task 14: Spotless + final check

- [ ] **Step 1: Apply Spotless formatting**

```bash
cd backend && ./gradlew spotlessApply 2>&1 | tail -10
```

- [ ] **Step 2: Run the full backend test suite one last time**

```bash
cd backend && ./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run frontend lint and format check**

```bash
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
cd frontend && npm run lint && npm run format:check 2>&1 | tail -10
```

Expected: no errors.

- [ ] **Step 4: Commit any formatting-only changes**

```bash
git add -u
git diff --cached --stat
git commit -m "chore: apply spotless and prettier formatting"
```

(Skip if nothing changed.)
