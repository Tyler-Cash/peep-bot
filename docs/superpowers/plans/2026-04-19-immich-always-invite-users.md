# Immich Always-Invite Users Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow operators to configure a list of Immich users (with roles) to be automatically invited to every album at creation time via Spring config.

**Architecture:** Add `AlbumUserRole` enum and `AlbumUser` record to the `immich` package; add `alwaysInviteUsers` list to `ImmichConfiguration`; add `addUsersToAlbum()` to `ImmichService`; call it from `PrepareAlbumOperation` only when the album is freshly created.

**Tech Stack:** Spring Boot 3.5.8 / Java 21, Lombok, MockRestServiceServer (tests), Mockito (tests), Gradle + Spotless (Palantir style)

---

### Task 1: Add `AlbumUserRole` and `AlbumUser` types

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/immich/AlbumUserRole.java`
- Create: `backend/src/main/java/dev/tylercash/event/immich/AlbumUser.java`

- [ ] **Step 1: Create `AlbumUserRole.java`**

```java
package dev.tylercash.event.immich;

import com.fasterxml.jackson.annotation.JsonValue;

public enum AlbumUserRole {
    EDITOR("editor"),
    VIEWER("viewer");

    private final String value;

    AlbumUserRole(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
```

- [ ] **Step 2: Create `AlbumUser.java`**

```java
package dev.tylercash.event.immich;

public record AlbumUser(String userId, AlbumUserRole role) {}
```

- [ ] **Step 3: Apply Spotless formatting**

Run from `backend/`:
```bash
./gradlew spotlessApply
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/immich/AlbumUserRole.java \
        backend/src/main/java/dev/tylercash/event/immich/AlbumUser.java
git commit -m "feat(immich): add AlbumUser record and AlbumUserRole enum"
```

---

### Task 2: Add `alwaysInviteUsers` to `ImmichConfiguration`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/immich/ImmichConfiguration.java`

- [ ] **Step 1: Add the field to `ImmichConfiguration`**

Add the import and field. The class already uses `@Data` (Lombok generates the getter/setter automatically).

Add import at the top:
```java
import java.util.ArrayList;
import java.util.List;
```

Add field after `albumNamePrefix`:
```java
private List<AlbumUser> alwaysInviteUsers = new ArrayList<>();
```

Full file after change:
```java
package dev.tylercash.event.immich;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.immich")
public class ImmichConfiguration {
    private String baseUrl;
    private String apiKey;
    private boolean enabled = false;
    private String albumNamePrefix = "";
    private List<AlbumUser> alwaysInviteUsers = new ArrayList<>();

    @PostConstruct
    public void validate() {
        if (enabled) {
            log.info("Immich integration enabled with base URL: {}", baseUrl);
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException(
                        "Immich is enabled but 'dev.tylercash.immich.base-url' is not configured");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                        "Immich is enabled but 'dev.tylercash.immich.api-key' is not configured");
            }
        } else {
            log.info("Immich integration disabled");
        }
    }
}
```

- [ ] **Step 2: Apply Spotless and compile-check**

```bash
cd backend && ./gradlew spotlessApply compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/immich/ImmichConfiguration.java
git commit -m "feat(immich): add alwaysInviteUsers config to ImmichConfiguration"
```

---

### Task 3: Add and test `ImmichService.addUsersToAlbum()`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/immich/ImmichService.java`
- Modify: `backend/src/test/java/dev/tylercash/event/immich/ImmichServiceTest.java`

- [ ] **Step 1: Write the failing tests**

Add these four tests to `ImmichServiceTest` (inside the class, after the existing `addAssetsToAlbum` tests):

```java
@Test
@DisplayName("addUsersToAlbum: makes PUT call with correct payload")
void addUsersToAlbumSuccess() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("https://immich.example.com/api/albums/album-123/users"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(content().json("{\"albumUsers\":[{\"userId\":\"user-1\",\"role\":\"editor\"}]}"))
            .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

    ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
    service.addUsersToAlbum("album-123", List.of(new AlbumUser("user-1", AlbumUserRole.EDITOR)));

    server.verify();
}

@Test
@DisplayName("addUsersToAlbum: empty list skips HTTP call")
void addUsersToAlbumEmptyListSkips() {
    ImmichService service =
            new ImmichService(enabledConfig(), RestClient.builder().build(), noWaitRetry());
    service.addUsersToAlbum("album-123", List.of()); // no exception = pass
}

@Test
@DisplayName("addUsersToAlbum: disabled skips HTTP call")
void addUsersToAlbumDisabledSkips() {
    ImmichService service =
            new ImmichService(disabledConfig(), RestClient.builder().build(), noWaitRetry());
    service.addUsersToAlbum("album-123", List.of(new AlbumUser("user-1", AlbumUserRole.VIEWER)));
}

@Test
@DisplayName("addUsersToAlbum: server error does not throw")
void addUsersToAlbumServerErrorDoesNotThrow() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("https://immich.example.com/api/albums/album-123/users"))
            .andRespond(withServerError());

    ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
    service.addUsersToAlbum("album-123", List.of(new AlbumUser("user-1", AlbumUserRole.EDITOR)));

    server.verify();
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.immich.ImmichServiceTest.addUsersToAlbumSuccess" \
  --tests "dev.tylercash.event.immich.ImmichServiceTest.addUsersToAlbumEmptyListSkips" \
  --tests "dev.tylercash.event.immich.ImmichServiceTest.addUsersToAlbumDisabledSkips" \
  --tests "dev.tylercash.event.immich.ImmichServiceTest.addUsersToAlbumServerErrorDoesNotThrow"
```

Expected: FAIL — method `addUsersToAlbum` does not exist yet.

- [ ] **Step 3: Implement `addUsersToAlbum` in `ImmichService`**

Add the method after `addAssetsToAlbum` (`Map` and `List` are already imported in the file):

```java
@Observed(name = "immich.add-users-to-album")
public void addUsersToAlbum(String albumId, List<AlbumUser> users) {
    if (!immichConfiguration.isEnabled() || users.isEmpty()) {
        return;
    }
    try {
        List<Map<String, Object>> albumUsers = users.stream()
                .map(u -> Map.<String, Object>of("userId", u.userId(), "role", u.role().getValue()))
                .toList();
        immichRestClient
                .put()
                .uri("/api/albums/{albumId}/users", albumId)
                .body(Map.of("albumUsers", albumUsers))
                .retrieve()
                .toBodilessEntity();
        log.info("Added {} users to Immich album {}", users.size(), albumId);
    } catch (Exception e) {
        log.warn("Failed to add users to Immich album {}", albumId, e);
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.immich.ImmichServiceTest"
```

Expected: All tests in `ImmichServiceTest` PASS.

- [ ] **Step 5: Apply Spotless**

```bash
cd backend && ./gradlew spotlessApply
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/immich/ImmichService.java \
        backend/src/test/java/dev/tylercash/event/immich/ImmichServiceTest.java
git commit -m "feat(immich): add addUsersToAlbum to ImmichService"
```

---

### Task 4: Call `addUsersToAlbum` from `PrepareAlbumOperation`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/event/statemachine/operation/PrepareAlbumOperation.java`
- Modify: `backend/src/test/java/dev/tylercash/event/event/statemachine/operation/PrepareAlbumOperationTest.java`

- [ ] **Step 1: Write the failing tests**

Add these two tests to `PrepareAlbumOperationTest` (add needed imports: `AlbumUser`, `AlbumUserRole`, `anyList`):

```java
import dev.tylercash.event.immich.AlbumUser;
import dev.tylercash.event.immich.AlbumUserRole;
import static org.mockito.ArgumentMatchers.anyList;
import java.util.List;
```

Tests to add:

```java
@Test
@DisplayName("action calls addUsersToAlbum with configured users when album is freshly created")
void action_invitesConfiguredUsersOnAlbumCreation() {
    List<AlbumUser> users = List.of(new AlbumUser("user-1", AlbumUserRole.EDITOR));
    immichConfiguration.setAlwaysInviteUsers(users);

    Event event = new Event();
    event.setName("Test");
    event.setDescription("Desc");

    when(immichService.createAlbum(anyString(), anyString())).thenReturn(Optional.of("album-id"));
    when(immichService.createSharedLink("album-id")).thenReturn(Optional.of("share-key"));

    operation.action().execute(contextWithEvent(event));

    verify(immichService).addUsersToAlbum("album-id", users);
}

@Test
@DisplayName("action does not call addUsersToAlbum when album already existed")
void action_doesNotInviteUsersWhenAlbumAlreadyExists() {
    immichConfiguration.setAlwaysInviteUsers(List.of(new AlbumUser("user-1", AlbumUserRole.VIEWER)));

    Event event = new Event();
    event.setName("Test");
    event.setImmichAlbumId("album-id");
    event.setImmichShareKey("share-key");

    operation.action().execute(contextWithEvent(event));

    verify(immichService, never()).addUsersToAlbum(anyString(), anyList());
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd backend && ./gradlew test \
  --tests "dev.tylercash.event.event.statemachine.operation.PrepareAlbumOperationTest.action_invitesConfiguredUsersOnAlbumCreation" \
  --tests "dev.tylercash.event.event.statemachine.operation.PrepareAlbumOperationTest.action_doesNotInviteUsersWhenAlbumAlreadyExists"
```

Expected: FAIL — `addUsersToAlbum` is not called yet.

- [ ] **Step 3: Update `PrepareAlbumOperation.action()`**

Replace the `if (event.getImmichAlbumId() == null)` block with one that calls `addUsersToAlbum` inside the `ifPresent` callback (so it only runs when the album was just created):

```java
if (event.getImmichAlbumId() == null) {
    immichService
            .createAlbum(event.getName(), event.getDescription())
            .ifPresent(albumId -> {
                event.setImmichAlbumId(albumId);
                immichService.addUsersToAlbum(albumId, immichConfiguration.getAlwaysInviteUsers());
            });
    progressMade = event.getImmichAlbumId() != null;
}
```

Full `action()` method after change:

```java
public Action<EventState, EventStateMachineEvent> action() {
    return context -> {
        Event event = context.getExtendedState().get("event", Event.class);
        boolean progressMade = false;

        if (event.getImmichAlbumId() == null) {
            immichService
                    .createAlbum(event.getName(), event.getDescription())
                    .ifPresent(albumId -> {
                        event.setImmichAlbumId(albumId);
                        immichService.addUsersToAlbum(albumId, immichConfiguration.getAlwaysInviteUsers());
                    });
            progressMade = event.getImmichAlbumId() != null;
        }

        if (event.getImmichAlbumId() != null && event.getImmichShareKey() == null) {
            immichService.createSharedLink(event.getImmichAlbumId()).ifPresent(event::setImmichShareKey);
            progressMade = progressMade || event.getImmichShareKey() != null;
        }

        if (event.getImmichAlbumId() != null && event.getImmichShareKey() != null) {
            log.info(
                    "Album prepared for event '{}': albumId={}, shareKey={}",
                    event.getName(),
                    event.getImmichAlbumId(),
                    event.getImmichShareKey());
            event.setState(EventState.POST_ALBUM_READY);
            eventRepository.save(event);
            return;
        }

        log.warn(
                "Album preparation incomplete for event '{}': albumId={}, shareKey={}",
                event.getName(),
                event.getImmichAlbumId(),
                event.getImmichShareKey());
        if (progressMade) {
            eventRepository.save(event);
        }
    };
}
```

- [ ] **Step 4: Run all tests to confirm everything passes**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.event.statemachine.operation.PrepareAlbumOperationTest"
```

Expected: All 6 tests in `PrepareAlbumOperationTest` PASS.

- [ ] **Step 5: Run full test suite**

```bash
cd backend && ./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 6: Apply Spotless**

```bash
cd backend && ./gradlew spotlessApply
```

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/event/statemachine/operation/PrepareAlbumOperation.java \
        backend/src/test/java/dev/tylercash/event/event/statemachine/operation/PrepareAlbumOperationTest.java
git commit -m "feat(immich): invite configured users to album on creation"
```
