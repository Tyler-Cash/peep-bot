# Event Cover Image (Google Places Photo) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When an event has a Google Place-backed location, fetch a photo from the Places API once at write-time, store the bytes on the event row, and render it as the Discord embed image.

**Architecture:** On `EventService.createEvent` / `updateEvent`, if `locationPlaceId` is set and differs from the place we last fetched a photo for, call the Places Photo API, store bytes + content-type + the place_id-they-belong-to on the `event` row. A public, cacheable backend endpoint `GET /api/events/{id}/cover` serves the bytes. A Next.js BFF route `frontend/src/app/api/events/[id]/cover/route.ts` proxies to the backend and is the URL Discord actually fetches — this matches the existing `api/avatar/[snowflake]` proxy pattern and keeps the backend off the public internet. `EmbedRenderer` builds the URL from `frontendUrl` (already injected) + `api/events/{id}/cover`. Failures (Places error, no photo, timeout) are logged and skipped — the event still saves.

**Tech Stack:** Spring Boot 3.5.8, Java 21, JDA 5.6.1, Liquibase, Spring `RestClient`, JUnit 5, WireMock for HTTP stubs; Next.js 15 route handler for the BFF proxy.

---

## File Structure

**New:**
- `backend/src/main/resources/db/changelog/changes/<next>-add-event-cover.yaml` — Liquibase changeset adding three columns to `event`: `cover_image_bytes` (bytea), `cover_image_content_type` (varchar 64), `cover_image_place_id` (varchar 256).
- `backend/src/main/java/dev/tylercash/event/places/PlacesPhotoClient.java` — thin Spring component that calls the Google Places API (new v1 endpoint) for a place_id and returns `Optional<PhotoBytes>`.
- `backend/src/main/java/dev/tylercash/event/places/PhotoBytes.java` — record holding `byte[] bytes, String contentType`.
- `backend/src/main/java/dev/tylercash/event/event/CoverImageService.java` — orchestration: given an `Event`, decide if a refetch is needed, call `PlacesPhotoClient`, mutate the event entity. Centralises the "only refetch when place_id changed" rule.
- `backend/src/main/java/dev/tylercash/event/event/EventCoverController.java` — `GET /api/events/{id}/cover`, returns `ResponseEntity<byte[]>` with content-type and `Cache-Control: public, max-age=86400`.
- `backend/src/test/java/dev/tylercash/event/places/PlacesPhotoClientTest.java` — WireMock-based HTTP tests.
- `backend/src/test/java/dev/tylercash/event/event/CoverImageServiceTest.java` — unit tests for the refetch decision.
- `backend/src/test/java/dev/tylercash/event/event/EventCoverControllerTest.java` — slice test for the endpoint.

**Modified:**
- `backend/src/main/resources/db/changelog/db.changelog-master.yaml` — include the new changeset.
- `backend/src/main/java/dev/tylercash/event/event/model/Event.java` — three new fields (`coverImageBytes`, `coverImageContentType`, `coverImagePlaceId`), excluded from `toString` and from the `copy(Event)` constructor's intent only if they shouldn't propagate (they should — keep them).
- `backend/src/main/java/dev/tylercash/event/event/EventService.java` — call `coverImageService.refreshIfNeeded(event)` in both `createEvent` (before `eventRepository.save`) and `updateEvent` (before `discordService.updateEventMessage`).
- `backend/src/main/java/dev/tylercash/event/discord/EmbedRenderer.java` — accept `coverImageUrl` (nullable) via constructor; call `embed.setImage(coverImageUrl)` when non-null.
- `backend/src/main/java/dev/tylercash/event/discord/EmbedService.java` — pass cover URL through to `EmbedRenderer`. Builds URL as `backendPublicUrl + "/api/events/" + event.getId() + "/cover"` only if `event.getCoverImageBytes() != null`.
- `backend/src/main/java/dev/tylercash/event/security/WebSecurityConfig.java` — `permitAll` for `/events/*/cover` (Discord fetches it without a session).
- `backend/src/main/resources/application.yaml` — add defaults: `dev.tylercash.places.api-key: ""`, `dev.tylercash.places.timeout: 5s`, `dev.tylercash.backend.public-url: "https://event.tylercash.dev"`.

---

### Task 1: Liquibase migration for cover columns

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260503-add-event-cover.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append `include` for new file)

Note: this repo currently keeps all changesets inline in `db.changelog-master.yaml`. Match that pattern by appending the changeset directly to the master file rather than creating a new include — verify the existing convention before deciding.

- [ ] **Step 1: Add changeset to `db.changelog-master.yaml`**

Append at the end of the `databaseChangeLog` list:

```yaml
  - changeSet:
      id: add cover image to event
      author: tylercash
      changes:
        - addColumn:
            tableName: event
            columns:
              - column:
                  name: cover_image_bytes
                  type: bytea
              - column:
                  name: cover_image_content_type
                  type: varchar(64)
              - column:
                  name: cover_image_place_id
                  type: varchar(256)
```

- [ ] **Step 2: Run backend tests to confirm migration applies cleanly**

Run: `./gradlew test --tests '*EventRepository*'` (or any test that boots the context).
Expected: PASS, no Liquibase errors in logs.

- [ ] **Step 3: Add JPA fields on `Event.java`**

In `backend/src/main/java/dev/tylercash/event/event/model/Event.java`, add:

```java
@Column(name = "cover_image_bytes")
@Lob
@ToString.Exclude
private byte[] coverImageBytes;

@Column(name = "cover_image_content_type")
private String coverImageContentType;

@Column(name = "cover_image_place_id")
private String coverImagePlaceId;
```

Then add the three to the `Event(Event event)` copy constructor:

```java
this.coverImageBytes = event.getCoverImageBytes();
this.coverImageContentType = event.getCoverImageContentType();
this.coverImagePlaceId = event.getCoverImagePlaceId();
```

- [ ] **Step 4: Run full test suite**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/changelog/db.changelog-master.yaml backend/src/main/java/dev/tylercash/event/event/model/Event.java
git commit -m "feat(event): add cover image columns to event table"
```

---

### Task 2: `PlacesPhotoClient` — Google Places HTTP client

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/places/PhotoBytes.java`
- Create: `backend/src/main/java/dev/tylercash/event/places/PlacesPhotoClient.java`
- Test: `backend/src/test/java/dev/tylercash/event/places/PlacesPhotoClientTest.java`

The Google Places API v1 photo flow is two calls:
1. `GET https://places.googleapis.com/v1/places/{place_id}?fields=photos&key={api_key}` → returns `{"photos":[{"name":"places/PLACE_ID/photos/PHOTO_REFERENCE",...}]}`.
2. `GET https://places.googleapis.com/v1/{photo.name}/media?maxHeightPx=720&key={api_key}` → returns the image bytes (with `Content-Type` like `image/jpeg`). Note: this returns 302 to a signed Google URL by default; pass `skipHttpRedirect=true` to receive bytes directly, OR follow the redirect (RestClient does this by default).

- [ ] **Step 1: Write the failing test**

```java
// PlacesPhotoClientTest.java
package dev.tylercash.event.places;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

class PlacesPhotoClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @Test
    void fetchesPhotoBytesForPlaceId() {
        wm.stubFor(get(urlPathEqualTo("/v1/places/abc"))
                .willReturn(okJson("{\"photos\":[{\"name\":\"places/abc/photos/PHOTO_REF\"}]}")));
        wm.stubFor(get(urlPathEqualTo("/v1/places/abc/photos/PHOTO_REF/media"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "image/jpeg")
                        .withBody(new byte[] {1, 2, 3})));

        PlacesPhotoClient client = new PlacesPhotoClient(
                RestClient.builder().baseUrl(wm.baseUrl()).build(), "fake-key", java.time.Duration.ofSeconds(5));

        Optional<PhotoBytes> result = client.fetchPhoto("abc");

        assertThat(result).isPresent();
        assertThat(result.get().contentType()).isEqualTo("image/jpeg");
        assertThat(result.get().bytes()).containsExactly(1, 2, 3);
    }

    @Test
    void returnsEmptyWhenPlaceHasNoPhotos() {
        wm.stubFor(get(urlPathEqualTo("/v1/places/empty"))
                .willReturn(okJson("{\"photos\":[]}")));

        PlacesPhotoClient client = new PlacesPhotoClient(
                RestClient.builder().baseUrl(wm.baseUrl()).build(), "fake-key", java.time.Duration.ofSeconds(5));

        assertThat(client.fetchPhoto("empty")).isEmpty();
    }

    @Test
    void returnsEmptyOnHttpError() {
        wm.stubFor(get(urlPathEqualTo("/v1/places/bad"))
                .willReturn(aResponse().withStatus(500)));

        PlacesPhotoClient client = new PlacesPhotoClient(
                RestClient.builder().baseUrl(wm.baseUrl()).build(), "fake-key", java.time.Duration.ofSeconds(5));

        assertThat(client.fetchPhoto("bad")).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to confirm it fails (compile error — class doesn't exist)**

Run: `./gradlew test --tests 'dev.tylercash.event.places.PlacesPhotoClientTest'`
Expected: FAIL — `PlacesPhotoClient` not found.

- [ ] **Step 3: Add `PhotoBytes` record**

```java
// PhotoBytes.java
package dev.tylercash.event.places;

public record PhotoBytes(byte[] bytes, String contentType) {}
```

- [ ] **Step 4: Implement `PlacesPhotoClient`**

```java
// PlacesPhotoClient.java
package dev.tylercash.event.places;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class PlacesPhotoClient {
    private static final String DEFAULT_BASE_URL = "https://places.googleapis.com";
    private static final int MAX_HEIGHT_PX = 720;

    private final RestClient restClient;
    private final String apiKey;
    private final Duration timeout;

    public PlacesPhotoClient(
            RestClient.Builder builder,
            @Value("${dev.tylercash.places.api-key:}") String apiKey,
            @Value("${dev.tylercash.places.timeout:5s}") Duration timeout) {
        this(builder.baseUrl(DEFAULT_BASE_URL).build(), apiKey, timeout);
    }

    PlacesPhotoClient(RestClient restClient, String apiKey, Duration timeout) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.timeout = timeout;
    }

    public Optional<PhotoBytes> fetchPhoto(String placeId) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Places API key not configured; skipping photo fetch for place {}", placeId);
            return Optional.empty();
        }
        try {
            JsonNode place = restClient
                    .get()
                    .uri("/v1/places/{id}?fields=photos&key={key}", placeId, apiKey)
                    .retrieve()
                    .body(JsonNode.class);
            if (place == null || !place.has("photos") || place.get("photos").isEmpty()) {
                return Optional.empty();
            }
            String photoName = place.get("photos").get(0).get("name").asText();
            ResponseEntity<byte[]> media = restClient
                    .get()
                    .uri("/v1/{name}/media?maxHeightPx={h}&key={key}", photoName, MAX_HEIGHT_PX, apiKey)
                    .retrieve()
                    .toEntity(byte[].class);
            byte[] body = media.getBody();
            if (body == null || body.length == 0) {
                return Optional.empty();
            }
            String contentType = media.getHeaders().getContentType() != null
                    ? media.getHeaders().getContentType().toString()
                    : MediaType.IMAGE_JPEG_VALUE;
            return Optional.of(new PhotoBytes(body, contentType));
        } catch (Exception e) {
            log.warn("Failed to fetch Places photo for place_id {}: {}", placeId, e.getMessage());
            return Optional.empty();
        }
    }
}
```

Note: `RestClient` does not enforce timeouts directly; the `timeout` field is currently unused. If we want hard timeouts, wire a `ClientHttpRequestFactory` configured from `timeout` (use `JdkClientHttpRequestFactory` with an `HttpClient` built with `connectTimeout`). Add this wiring during Step 4 if the team wants it; otherwise document as a known gap and move on.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'dev.tylercash.event.places.PlacesPhotoClientTest'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/places/ backend/src/test/java/dev/tylercash/event/places/
git commit -m "feat(places): add Google Places Photo API client"
```

---

### Task 3: `CoverImageService` — refetch decision + entity mutation

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/event/CoverImageService.java`
- Test: `backend/src/test/java/dev/tylercash/event/event/CoverImageServiceTest.java`

The rule: refetch if `event.locationPlaceId` is non-blank AND differs from `event.coverImagePlaceId`. Clear cover columns if `locationPlaceId` is blank/null but cover exists.

- [ ] **Step 1: Write failing tests**

```java
// CoverImageServiceTest.java
package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.places.PhotoBytes;
import dev.tylercash.event.places.PlacesPhotoClient;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CoverImageServiceTest {

    @Test
    void fetchesPhotoWhenPlaceIdSetAndCoverMissing() {
        PlacesPhotoClient client = mock(PlacesPhotoClient.class);
        when(client.fetchPhoto("abc"))
                .thenReturn(Optional.of(new PhotoBytes(new byte[] {9, 9}, "image/jpeg")));
        CoverImageService svc = new CoverImageService(client);

        Event event = new Event();
        event.setLocationPlaceId("abc");

        svc.refreshIfNeeded(event);

        assertThat(event.getCoverImageBytes()).containsExactly(9, 9);
        assertThat(event.getCoverImageContentType()).isEqualTo("image/jpeg");
        assertThat(event.getCoverImagePlaceId()).isEqualTo("abc");
    }

    @Test
    void skipsFetchWhenPlaceIdUnchanged() {
        PlacesPhotoClient client = mock(PlacesPhotoClient.class);
        CoverImageService svc = new CoverImageService(client);

        Event event = new Event();
        event.setLocationPlaceId("abc");
        event.setCoverImagePlaceId("abc");
        event.setCoverImageBytes(new byte[] {1});
        event.setCoverImageContentType("image/jpeg");

        svc.refreshIfNeeded(event);

        verifyNoInteractions(client);
        assertThat(event.getCoverImageBytes()).containsExactly(1);
    }

    @Test
    void clearsCoverWhenPlaceIdRemoved() {
        PlacesPhotoClient client = mock(PlacesPhotoClient.class);
        CoverImageService svc = new CoverImageService(client);

        Event event = new Event();
        event.setLocationPlaceId(null);
        event.setCoverImagePlaceId("old");
        event.setCoverImageBytes(new byte[] {1});
        event.setCoverImageContentType("image/jpeg");

        svc.refreshIfNeeded(event);

        verifyNoInteractions(client);
        assertThat(event.getCoverImageBytes()).isNull();
        assertThat(event.getCoverImageContentType()).isNull();
        assertThat(event.getCoverImagePlaceId()).isNull();
    }

    @Test
    void leavesEventUnchangedWhenPlacesReturnsEmpty() {
        PlacesPhotoClient client = mock(PlacesPhotoClient.class);
        when(client.fetchPhoto("abc")).thenReturn(Optional.empty());
        CoverImageService svc = new CoverImageService(client);

        Event event = new Event();
        event.setLocationPlaceId("abc");

        svc.refreshIfNeeded(event);

        assertThat(event.getCoverImageBytes()).isNull();
        assertThat(event.getCoverImagePlaceId()).isNull();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'dev.tylercash.event.event.CoverImageServiceTest'`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement `CoverImageService`**

```java
// CoverImageService.java
package dev.tylercash.event.event;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.places.PhotoBytes;
import dev.tylercash.event.places.PlacesPhotoClient;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoverImageService {
    private final PlacesPhotoClient placesPhotoClient;

    public void refreshIfNeeded(Event event) {
        String placeId = event.getLocationPlaceId();
        boolean placeBlank = placeId == null || placeId.isBlank();

        if (placeBlank) {
            if (event.getCoverImageBytes() != null || event.getCoverImagePlaceId() != null) {
                event.setCoverImageBytes(null);
                event.setCoverImageContentType(null);
                event.setCoverImagePlaceId(null);
            }
            return;
        }

        if (Objects.equals(placeId, event.getCoverImagePlaceId()) && event.getCoverImageBytes() != null) {
            return;
        }

        Optional<PhotoBytes> photo = placesPhotoClient.fetchPhoto(placeId);
        if (photo.isEmpty()) {
            log.info("No Places photo available for event {} place_id {}", event.getId(), placeId);
            return;
        }
        event.setCoverImageBytes(photo.get().bytes());
        event.setCoverImageContentType(photo.get().contentType());
        event.setCoverImagePlaceId(placeId);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'dev.tylercash.event.event.CoverImageServiceTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/event/CoverImageService.java backend/src/test/java/dev/tylercash/event/event/CoverImageServiceTest.java
git commit -m "feat(event): add CoverImageService for Places-backed cover image"
```

---

### Task 4: Wire `CoverImageService` into `EventService`

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/event/EventService.java`
- Test: `backend/src/test/java/dev/tylercash/event/event/EventServiceTest.java` (existing — extend)

- [ ] **Step 1: Inject `CoverImageService` in `EventService`**

Add `private final CoverImageService coverImageService;` to the field list (Lombok `@RequiredArgsConstructor` will pick it up).

- [ ] **Step 2: Call `refreshIfNeeded` in `createEvent` and `updateEvent`**

In `createEvent`, before `eventRepository.save(event);`:

```java
coverImageService.refreshIfNeeded(event);
```

In `updateEvent`, before `discordService.updateEventMessage(event);`:

```java
coverImageService.refreshIfNeeded(event);
```

- [ ] **Step 3: Add a focused test in `EventServiceTest`**

Use the existing test class style (mock `CoverImageService` and verify it's invoked). Sample:

```java
@Test
void updateEventTriggersCoverRefresh() {
    Event event = new Event();
    event.setId(UUID.randomUUID());
    event.setLocationPlaceId("abc");

    eventService.updateEvent(event);

    verify(coverImageService).refreshIfNeeded(event);
}
```

If `EventServiceTest` doesn't exist or uses a different style, follow the closest existing pattern in the package.

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests '*EventServiceTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/event/EventService.java backend/src/test/java/dev/tylercash/event/event/EventServiceTest.java
git commit -m "feat(event): refresh cover image on event create/update"
```

---

### Task 5: Public `GET /api/events/{id}/cover` endpoint

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/event/EventCoverController.java`
- Modify: `backend/src/main/java/dev/tylercash/event/security/WebSecurityConfig.java`
- Test: `backend/src/test/java/dev/tylercash/event/event/EventCoverControllerTest.java`

- [ ] **Step 1: Allow `/events/*/cover` without auth**

In `WebSecurityConfig.java`, in the `permitAll` chain, add:

```java
.requestMatchers("/events/*/cover")
.permitAll()
```

(Note: backend context path is `/api/`, so the matcher is relative — `/events/*/cover`.)

- [ ] **Step 2: Write the failing test**

```java
// EventCoverControllerTest.java
package dev.tylercash.event.event;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.tylercash.event.event.model.Event;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import dev.tylercash.event.event.repository.EventRepository;

@WebMvcTest(controllers = EventCoverController.class)
class EventCoverControllerTest {
    @Autowired MockMvc mvc;
    @MockBean EventRepository eventRepository;

    @Test
    void returnsImageBytesWhenCoverPresent() throws Exception {
        UUID id = UUID.randomUUID();
        Event event = new Event();
        event.setId(id);
        event.setCoverImageBytes(new byte[] {1, 2, 3});
        event.setCoverImageContentType("image/jpeg");
        org.mockito.Mockito.when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        mvc.perform(get("/events/{id}/cover", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "public, max-age=86400"))
                .andExpect(content().bytes(new byte[] {1, 2, 3}));
    }

    @Test
    void returns404WhenNoCover() throws Exception {
        UUID id = UUID.randomUUID();
        Event event = new Event();
        event.setId(id);
        org.mockito.Mockito.when(eventRepository.findById(id)).thenReturn(Optional.of(event));

        mvc.perform(get("/events/{id}/cover", id)).andExpect(status().isNotFound());
    }

    @Test
    void returns404WhenEventMissing() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.when(eventRepository.findById(id)).thenReturn(Optional.empty());

        mvc.perform(get("/events/{id}/cover", id)).andExpect(status().isNotFound());
    }
}
```

Verify the actual repository class name and package — replace the import if different.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests 'dev.tylercash.event.event.EventCoverControllerTest'`
Expected: FAIL — controller not found.

- [ ] **Step 4: Implement `EventCoverController`**

```java
// EventCoverController.java
package dev.tylercash.event.event;

import dev.tylercash.event.event.model.Event;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
// import the actual EventRepository — confirm package
import dev.tylercash.event.event.repository.EventRepository;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventCoverController {
    private final EventRepository eventRepository;

    @GetMapping("/{id}/cover")
    public ResponseEntity<byte[]> getCover(@PathVariable UUID id) {
        return eventRepository
                .findById(id)
                .filter(e -> e.getCoverImageBytes() != null && e.getCoverImageBytes().length > 0)
                .map(this::toResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<byte[]> toResponse(Event event) {
        MediaType type = event.getCoverImageContentType() != null
                ? MediaType.parseMediaType(event.getCoverImageContentType())
                : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(event.getCoverImageBytes());
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests 'dev.tylercash.event.event.EventCoverControllerTest'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/event/EventCoverController.java backend/src/main/java/dev/tylercash/event/security/WebSecurityConfig.java backend/src/test/java/dev/tylercash/event/event/EventCoverControllerTest.java
git commit -m "feat(event): expose public /events/{id}/cover endpoint"
```

---

### Task 6: BFF proxy route for cover bytes

**Files:**
- Create: `frontend/src/app/api/events/[id]/cover/route.ts`

Mirrors `api/avatar/[snowflake]/route.ts` but: (a) no session cookie required (backend endpoint is `permitAll`), (b) cache-control overridden to `public, max-age=86400`.

- [ ] **Step 1: Implement the route**

```ts
const BACKEND_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080/api";

export async function GET(
  _req: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  const upstream = await fetch(`${BACKEND_BASE}/events/${encodeURIComponent(id)}/cover`);
  if (!upstream.ok) {
    return new Response(null, { status: upstream.status });
  }
  const ct = upstream.headers.get("content-type") ?? "image/jpeg";
  const body = await upstream.arrayBuffer();
  return new Response(body, {
    headers: {
      "content-type": ct,
      "cache-control": "public, max-age=86400",
    },
  });
}
```

- [ ] **Step 2: Smoke test**

Run: `cd frontend && npm run typecheck && npm run lint`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/api/events/
git commit -m "feat(frontend): proxy event cover image bytes from backend"
```

---

### Task 7: Render cover image in Discord embed

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/EmbedRenderer.java`
- Modify: `backend/src/main/java/dev/tylercash/event/discord/EmbedService.java`
- Modify: `backend/src/main/resources/application.yaml`
- Test: existing `EmbedRendererTest` (or equivalent — extend)

- [ ] **Step 1: Add config property defaults**

In `application.yaml`, add under `dev.tylercash`:

```yaml
places:
  api-key: ""
  timeout: 5s
```

(`frontend.hostname` already exists and is what we'll use to build the cover URL.)

- [ ] **Step 2: Add `coverImageUrl` parameter to `EmbedRenderer`**

Modify the constructor field list to add `private final String coverImageUrl;` (nullable) and update existing call sites in `EmbedService` to pass it. In `getEmbedBuilder()`, after `setColor(Color.YELLOW)`:

```java
if (coverImageUrl != null) {
    embed.setImage(coverImageUrl);
}
```

- [ ] **Step 3: Build the URL in `EmbedService`**

Reuse the `frontendUrl` value already passed into the renderer. When constructing the renderer:

```java
String coverUrl = (event.getCoverImageBytes() != null && event.getCoverImageBytes().length > 0 && event.getId() != null)
        ? frontendUrl + "api/events/" + event.getId() + "/cover"
        : null;
```

Pass `coverUrl` into the `EmbedRenderer` constructor. (Note: existing code shows `frontendUrl` already includes a trailing slash — verify before concatenating.)

- [ ] **Step 4: Update existing renderer test**

Find the existing `EmbedRendererTest` (search: `find backend/src/test -name 'EmbedRendererTest*'`). Add a test that constructs the renderer with a non-null `coverImageUrl` and asserts the resulting `MessageEmbed.getImage().getUrl()` matches.

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests '*EmbedRendererTest'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/dev/tylercash/event/discord/ backend/src/main/resources/application.yaml backend/src/test/java/dev/tylercash/event/discord/
git commit -m "feat(discord): render Places cover image as embed image"
```

---

### Task 8: End-to-end verification

**Files:** none — manual verification only.

- [ ] **Step 1: Add API key locally**

In `backend/src/main/resources/application-local.yaml`, add:

```yaml
dev.tylercash:
  places:
    api-key: "<your-google-cloud-api-key-with-Places-API-enabled>"
```

(For Discord to actually fetch a localhost URL, the backend must be tunnelled — e.g. `cloudflared` or `ngrok`. If you can't tunnel, verify the bytes endpoint works via `curl` and skip the Discord-rendered check until staging.)

- [ ] **Step 2: Boot backend + frontend**

Run the standard local startup (per CLAUDE.md). Create a new event in the UI and pick a real Place from the venue search.

- [ ] **Step 3: Verify the bytes**

```bash
curl -I http://localhost:8080/api/events/<event-id>/cover
```

Expected: `200 OK`, `Content-Type: image/jpeg` (or similar), `Cache-Control: public, max-age=86400`.

- [ ] **Step 4: Verify the Discord embed**

Open Discord, find the event message, confirm the embed shows a large image of the venue.

- [ ] **Step 5: Verify the no-place case**

Create an event with a free-text location (no Place pick). Confirm `/cover` returns 404 and the Discord embed renders without an image (regression check).

---

## Self-Review

**Spec coverage:**
- Places photo fetch at write time → Tasks 2, 3, 4. ✓
- Bytes stored on event row → Task 1. ✓
- Stable cover URL served by backend → Task 5. ✓
- Embed rendering via `setImage` → Task 6. ✓
- Skip link preview work → not in plan. ✓

**Placeholder scan:** No TODOs. The `EventCoverControllerTest` has a "verify the actual repository class name" hedge — that's a real verification step the executor should do, not a placeholder for missing code.

**Type consistency:** `PhotoBytes(byte[] bytes, String contentType)` used consistently. `coverImageBytes`/`coverImageContentType`/`coverImagePlaceId` used identically across Tasks 1, 3, 5, 6. `refreshIfNeeded(Event)` consistent across Tasks 3 and 4.

**Known risks the executor should watch:**
- Existing repo convention is to keep all changesets in `db.changelog-master.yaml` rather than separate include files (verified during planning) — Task 1 follows that pattern.
- `RestClient` timeout is not enforced by default; Task 2 documents this gap rather than silently shipping a bug.
- The actual `EventRepository` class location must be confirmed; Task 5's import is best-guess.
