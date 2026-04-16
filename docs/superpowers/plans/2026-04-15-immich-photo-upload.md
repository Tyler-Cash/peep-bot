# Immich Photo/Video Auto-Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically upload photos and videos posted in Discord event channels (after event start) to the event's Immich album.

**Architecture:** A new JDA `MessageReceivedListener` watches all incoming Discord messages, filters to event channels that have started and have an Immich album, downloads image/video attachments as byte arrays, uploads them to Immich via two new `ImmichService` methods, then adds them to the event's album in a single batched call. Uploads are wrapped with a Resilience4j `Retry` (3 attempts, exponential backoff) to handle transient Immich outages.

**Tech Stack:** JDA 6 (`ListenerAdapter`, `MessageReceivedEvent`, `AttachmentProxy`), Resilience4j Retry 2.4.0, Spring `RestClient` (multipart), Spring `ByteArrayResource`

---

## File Map

| File | Action |
|---|---|
| `backend/build.gradle` | Add `resilience4j-retry:2.4.0` dependency |
| `backend/src/main/java/dev/tylercash/event/immich/ImmichAssetResponse.java` | **Create** — record holding Immich asset upload response |
| `backend/src/main/java/dev/tylercash/event/global/ServiceConfiguration.java` | Add `immichUploadRetry` Retry bean |
| `backend/src/main/java/dev/tylercash/event/immich/ImmichService.java` | Add `uploadAsset` and `addAssetsToAlbum` methods; inject `Retry` |
| `backend/src/main/java/dev/tylercash/event/discord/listener/MessageReceivedListener.java` | **Create** — JDA listener filtering and dispatching uploads |
| `backend/src/main/java/dev/tylercash/event/discord/ClientConfiguration.java` | Register `MessageReceivedListener` with JDA |
| `backend/src/test/java/dev/tylercash/event/immich/ImmichServiceTest.java` | Add tests for new methods; update constructor calls to pass `Retry` |
| `backend/src/test/java/dev/tylercash/event/discord/listener/MessageReceivedListenerTest.java` | **Create** — unit tests for the new listener |

---

## Task 1: Add Resilience4j Retry Dependency and Bean

**Files:**
- Modify: `backend/build.gradle`
- Modify: `backend/src/main/java/dev/tylercash/event/global/ServiceConfiguration.java`

- [ ] **Step 1: Add `resilience4j-retry` to `build.gradle`**

In `backend/build.gradle`, add after the existing `resilience4j-ratelimiter` line:

```groovy
implementation 'io.github.resilience4j:resilience4j-retry:2.4.0'
```

- [ ] **Step 2: Verify the dependency resolves**

```bash
cd backend && ./gradlew dependencies --configuration compileClasspath | grep resilience4j
```

Expected: both `resilience4j-ratelimiter` and `resilience4j-retry` appear.

- [ ] **Step 3: Add `immichUploadRetry` bean to `ServiceConfiguration`**

Add these imports to `ServiceConfiguration.java`:

```java
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
```

Add this bean method inside the `ServiceConfiguration` class (after the existing `notifyEventRoles` bean):

```java
@Bean
public Retry immichUploadRetry() {
    RetryConfig config = RetryConfig.custom()
            .maxAttempts(3)
            .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofSeconds(1), 2.0))
            .retryOnException(e -> e instanceof RestClientException && !(e instanceof HttpClientErrorException))
            .build();
    return Retry.of("immichUpload", config);
}
```

- [ ] **Step 4: Run tests to confirm no regressions**

```bash
cd backend && ./gradlew test
```

Expected: BUILD SUCCESSFUL, all existing tests pass.

- [ ] **Step 5: Commit**

```bash
cd backend && ./gradlew spotlessApply
git add backend/build.gradle backend/src/main/java/dev/tylercash/event/global/ServiceConfiguration.java
git commit -m "feat(immich): add resilience4j-retry dependency and immichUploadRetry bean"
```

---

## Task 2: Create ImmichAssetResponse and Update ImmichService

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/immich/ImmichAssetResponse.java`
- Modify: `backend/src/main/java/dev/tylercash/event/immich/ImmichService.java`
- Modify: `backend/src/test/java/dev/tylercash/event/immich/ImmichServiceTest.java`

- [ ] **Step 1: Write failing tests for `uploadAsset` and `addAssetsToAlbum`**

Append the following tests to `ImmichServiceTest.java`. Note that the `ImmichService` constructor now needs a third `Retry` argument — update the existing helper as shown.

First, add these imports to `ImmichServiceTest.java`:

```java
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
```

Add a helper method to `ImmichServiceTest` (used for all test instances — **update existing test methods** to use it):

```java
private Retry noWaitRetry() {
    return Retry.of("test", RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ZERO)
            .retryOnException(e -> e instanceof RestClientException && !(e instanceof HttpClientErrorException))
            .build());
}
```

**Update all existing test methods** that construct `ImmichService` directly. Change every occurrence of:

```java
ImmichService service = new ImmichService(enabledConfig(), builder.build());
```

to:

```java
ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
```

And for the `disabledConfigReturnsEmpty` test:

```java
ImmichService service = new ImmichService(disabledConfig(), restClient);
```

becomes:

```java
ImmichService service = new ImmichService(disabledConfig(), restClient, noWaitRetry());
```

Now add these new test methods:

```java
@Test
@DisplayName("uploadAsset: success returns asset ID")
void uploadAssetSuccess() throws Exception {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("https://immich.example.com/api/assets"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"id\":\"asset-abc\"}", MediaType.APPLICATION_JSON));

    ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
    Optional<String> result = service.uploadAsset("photo.jpg", new byte[]{1, 2, 3}, "image/jpeg");

    assertTrue(result.isPresent());
    assertEquals("asset-abc", result.get());
    server.verify();
}

@Test
@DisplayName("uploadAsset: disabled returns empty without HTTP calls")
void uploadAssetDisabledReturnsEmpty() {
    ImmichService service = new ImmichService(disabledConfig(), RestClient.builder().build(), noWaitRetry());
    assertTrue(service.uploadAsset("photo.jpg", new byte[]{1}, "image/jpeg").isEmpty());
}

@Test
@DisplayName("uploadAsset: server error after retries returns empty")
void uploadAssetServerErrorReturnsEmpty() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    // 3 attempts = 3 expectations
    server.expect(requestTo("https://immich.example.com/api/assets")).andRespond(withServerError());
    server.expect(requestTo("https://immich.example.com/api/assets")).andRespond(withServerError());
    server.expect(requestTo("https://immich.example.com/api/assets")).andRespond(withServerError());

    ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
    Optional<String> result = service.uploadAsset("photo.jpg", new byte[]{1, 2, 3}, "image/jpeg");

    assertTrue(result.isEmpty());
    server.verify();
}

@Test
@DisplayName("uploadAsset: 4xx error is not retried")
void uploadAssetClientErrorNotRetried() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    // Only 1 expectation — should not retry on 4xx
    server.expect(requestTo("https://immich.example.com/api/assets")).andRespond(withBadRequest());

    ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
    Optional<String> result = service.uploadAsset("photo.jpg", new byte[]{1, 2, 3}, "image/jpeg");

    assertTrue(result.isEmpty());
    server.verify();
}

@Test
@DisplayName("addAssetsToAlbum: success makes PUT call")
void addAssetsToAlbumSuccess() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("https://immich.example.com/api/albums/album-123/assets"))
            .andExpect(method(HttpMethod.PUT))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
    service.addAssetsToAlbum("album-123", List.of("asset-abc"));

    server.verify();
}

@Test
@DisplayName("addAssetsToAlbum: disabled skips HTTP call")
void addAssetsToAlbumDisabled() {
    ImmichService service = new ImmichService(disabledConfig(), RestClient.builder().build(), noWaitRetry());
    service.addAssetsToAlbum("album-123", List.of("asset-abc")); // no exception = pass
}

@Test
@DisplayName("addAssetsToAlbum: server error after retries does not throw")
void addAssetsToAlbumServerErrorDoesNotThrow() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("https://immich.example.com/api/albums/album-123/assets")).andRespond(withServerError());
    server.expect(requestTo("https://immich.example.com/api/albums/album-123/assets")).andRespond(withServerError());
    server.expect(requestTo("https://immich.example.com/api/albums/album-123/assets")).andRespond(withServerError());

    ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
    service.addAssetsToAlbum("album-123", List.of("asset-abc")); // must not throw

    server.verify();
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.immich.ImmichServiceTest"
```

Expected: FAILED — compilation errors because `ImmichAssetResponse` and new `ImmichService` methods don't exist yet.

- [ ] **Step 3: Create `ImmichAssetResponse.java`**

```java
package dev.tylercash.event.immich;

public record ImmichAssetResponse(String id) {}
```

- [ ] **Step 4: Implement new methods in `ImmichService.java`**

Add these imports to `ImmichService.java`:

```java
import io.github.resilience4j.retry.Retry;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
```

Update the `ImmichService` class to add the `Retry` field. Change the class declaration and constructor:

```java
@Slf4j
@Service
public class ImmichService {
    private final ImmichConfiguration immichConfiguration;
    private final RestClient immichRestClient;
    private final Retry immichUploadRetry;

    public ImmichService(ImmichConfiguration immichConfiguration, RestClient immichRestClient, Retry immichUploadRetry) {
        this.immichConfiguration = immichConfiguration;
        this.immichRestClient = immichRestClient;
        this.immichUploadRetry = immichUploadRetry;
    }
```

Add these two methods at the end of the class (before the closing `}`):

```java
@Observed(name = "immich.upload-asset")
public Optional<String> uploadAsset(String filename, byte[] data, String contentType) {
    if (!immichConfiguration.isEnabled()) {
        return Optional.empty();
    }
    Supplier<Optional<String>> upload = Retry.decorateSupplier(immichUploadRetry, () -> {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("deviceAssetId", filename);
        parts.add("deviceId", "peep-bot");
        String now = Instant.now().toString();
        parts.add("fileCreatedAt", now);
        parts.add("fileModifiedAt", now);
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        parts.add("assetData", new HttpEntity<>(new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, partHeaders));
        ImmichAssetResponse response = immichRestClient
                .post()
                .uri("/api/assets")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(ImmichAssetResponse.class);
        if (response == null || response.id() == null) {
            log.warn("Immich asset upload returned null response for: {}", filename);
            return Optional.empty();
        }
        log.info("Uploaded Immich asset {} for file: {}", response.id(), filename);
        return Optional.of(response.id());
    });
    try {
        return upload.get();
    } catch (Exception e) {
        log.error("Failed to upload asset to Immich after retries: {}", filename, e);
        return Optional.empty();
    }
}

@Observed(name = "immich.add-assets-to-album")
public void addAssetsToAlbum(String albumId, List<String> assetIds) {
    if (!immichConfiguration.isEnabled() || assetIds.isEmpty()) {
        return;
    }
    Runnable addAssets = Retry.decorateRunnable(immichUploadRetry, () ->
            immichRestClient
                    .put()
                    .uri("/api/albums/{albumId}/assets", albumId)
                    .body(Map.of("ids", assetIds))
                    .retrieve()
                    .toBodilessEntity());
    try {
        addAssets.run();
        log.info("Added {} assets to Immich album {}", assetIds.size(), albumId);
    } catch (Exception e) {
        log.warn("Failed to add {} assets to Immich album {} after retries", assetIds.size(), albumId, e);
    }
}
```

- [ ] **Step 5: Run tests**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.immich.ImmichServiceTest"
```

Expected: BUILD SUCCESSFUL, all ImmichServiceTest tests pass.

- [ ] **Step 6: Run full test suite to confirm no regressions**

```bash
cd backend && ./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd backend && ./gradlew spotlessApply
git add backend/src/main/java/dev/tylercash/event/immich/ImmichAssetResponse.java \
        backend/src/main/java/dev/tylercash/event/immich/ImmichService.java \
        backend/src/test/java/dev/tylercash/event/immich/ImmichServiceTest.java
git commit -m "feat(immich): add uploadAsset and addAssetsToAlbum with retry"
```

---

## Task 3: Create MessageReceivedListener

**Files:**
- Create: `backend/src/main/java/dev/tylercash/event/discord/listener/MessageReceivedListener.java`
- Create: `backend/src/test/java/dev/tylercash/event/discord/listener/MessageReceivedListenerTest.java`

- [ ] **Step 1: Write failing tests**

Create `backend/src/test/java/dev/tylercash/event/discord/listener/MessageReceivedListenerTest.java`:

```java
package dev.tylercash.event.discord.listener;

import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.immich.ImmichConfiguration;
import dev.tylercash.event.immich.ImmichService;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.utils.AttachmentProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageReceivedListenerTest {

    private Clock clock;
    private EventRepository eventRepository;
    private ImmichConfiguration immichConfiguration;
    private ImmichService immichService;
    private MessageReceivedListener listener;

    @BeforeEach
    void setUp() {
        // Clock fixed at 15:00 UTC — after a 13:00 event start
        clock = Clock.fixed(Instant.parse("2025-01-01T15:00:00Z"), ZoneId.of("UTC"));
        eventRepository = mock(EventRepository.class);
        immichConfiguration = new ImmichConfiguration();
        immichConfiguration.setEnabled(true);
        immichService = mock(ImmichService.class);
        listener = new MessageReceivedListener(clock, eventRepository, immichConfiguration, immichService);
    }

    private MessageReceivedEvent buildEvent(long channelId, List<Message.Attachment> attachments) {
        MessageReceivedEvent jdaEvent = mock(MessageReceivedEvent.class);
        Message message = mock(Message.class);
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        when(jdaEvent.getMessage()).thenReturn(message);
        when(jdaEvent.getChannel()).thenReturn(channel);
        when(channel.getIdLong()).thenReturn(channelId);
        when(message.getAttachments()).thenReturn(attachments);
        return jdaEvent;
    }

    private Message.Attachment imageAttachment(String filename, byte[] data) {
        Message.Attachment attachment = mock(Message.Attachment.class);
        AttachmentProxy proxy = mock(AttachmentProxy.class);
        when(attachment.getContentType()).thenReturn("image/jpeg");
        when(attachment.getFileName()).thenReturn(filename);
        when(attachment.getProxy()).thenReturn(proxy);
        when(proxy.download()).thenReturn(CompletableFuture.completedFuture(new ByteArrayInputStream(data)));
        return attachment;
    }

    private Event startedEventWithAlbum(long channelId) {
        Event event = new Event();
        event.setImmichAlbumId("album-123");
        event.setDateTime(ZonedDateTime.parse("2025-01-01T13:00:00Z")); // started 2h ago
        return event;
    }

    @Test
    void skipsWhenImmichDisabled() {
        immichConfiguration.setEnabled(false);
        listener.onMessageReceived(buildEvent(99L, List.of(mock(Message.Attachment.class))));
        verifyNoInteractions(eventRepository, immichService);
    }

    @Test
    void skipsWhenNoAttachments() {
        listener.onMessageReceived(buildEvent(99L, Collections.emptyList()));
        verifyNoInteractions(eventRepository, immichService);
    }

    @Test
    void skipsWhenChannelHasNoMatchingEvent() {
        when(eventRepository.findByChannelId(99L)).thenReturn(null);
        listener.onMessageReceived(buildEvent(99L, List.of(mock(Message.Attachment.class))));
        verifyNoInteractions(immichService);
    }

    @Test
    void skipsWhenEventHasNoAlbumId() {
        Event event = new Event();
        event.setImmichAlbumId(null);
        event.setDateTime(ZonedDateTime.parse("2025-01-01T13:00:00Z"));
        when(eventRepository.findByChannelId(99L)).thenReturn(event);
        listener.onMessageReceived(buildEvent(99L, List.of(mock(Message.Attachment.class))));
        verifyNoInteractions(immichService);
    }

    @Test
    void skipsWhenEventHasNotStartedYet() {
        Event event = new Event();
        event.setImmichAlbumId("album-123");
        event.setDateTime(ZonedDateTime.parse("2025-01-01T18:00:00Z")); // in the future
        when(eventRepository.findByChannelId(99L)).thenReturn(event);
        listener.onMessageReceived(buildEvent(99L, List.of(mock(Message.Attachment.class))));
        verifyNoInteractions(immichService);
    }

    @Test
    void skipsNonMediaAttachments() {
        Event event = startedEventWithAlbum(99L);
        when(eventRepository.findByChannelId(99L)).thenReturn(event);

        Message.Attachment docAttachment = mock(Message.Attachment.class);
        when(docAttachment.getContentType()).thenReturn("application/pdf");

        listener.onMessageReceived(buildEvent(99L, List.of(docAttachment)));
        verifyNoInteractions(immichService);
    }

    @Test
    void uploadsImageAndAddsToAlbum() {
        Event event = startedEventWithAlbum(99L);
        when(eventRepository.findByChannelId(99L)).thenReturn(event);

        byte[] imageData = new byte[]{1, 2, 3};
        Message.Attachment attachment = imageAttachment("photo.jpg", imageData);

        when(immichService.uploadAsset("photo.jpg", imageData, "image/jpeg"))
                .thenReturn(Optional.of("asset-456"));

        listener.onMessageReceived(buildEvent(99L, List.of(attachment)));

        verify(immichService).uploadAsset("photo.jpg", imageData, "image/jpeg");
        verify(immichService).addAssetsToAlbum("album-123", List.of("asset-456"));
    }

    @Test
    void uploadsMultipleAttachmentsInOneBatch() {
        Event event = startedEventWithAlbum(99L);
        when(eventRepository.findByChannelId(99L)).thenReturn(event);

        byte[] data1 = new byte[]{1};
        byte[] data2 = new byte[]{2};
        Message.Attachment att1 = imageAttachment("a.jpg", data1);
        Message.Attachment att2 = imageAttachment("b.jpg", data2);

        when(immichService.uploadAsset("a.jpg", data1, "image/jpeg")).thenReturn(Optional.of("id-1"));
        when(immichService.uploadAsset("b.jpg", data2, "image/jpeg")).thenReturn(Optional.of("id-2"));

        listener.onMessageReceived(buildEvent(99L, List.of(att1, att2)));

        verify(immichService).addAssetsToAlbum("album-123", List.of("id-1", "id-2"));
    }

    @Test
    void uploadsVideoAttachment() {
        Event event = startedEventWithAlbum(99L);
        when(eventRepository.findByChannelId(99L)).thenReturn(event);

        Message.Attachment attachment = mock(Message.Attachment.class);
        AttachmentProxy proxy = mock(AttachmentProxy.class);
        byte[] videoData = new byte[]{9, 8, 7};
        when(attachment.getContentType()).thenReturn("video/mp4");
        when(attachment.getFileName()).thenReturn("clip.mp4");
        when(attachment.getProxy()).thenReturn(proxy);
        when(proxy.download()).thenReturn(CompletableFuture.completedFuture(new ByteArrayInputStream(videoData)));
        when(immichService.uploadAsset("clip.mp4", videoData, "video/mp4")).thenReturn(Optional.of("asset-vid"));

        listener.onMessageReceived(buildEvent(99L, List.of(attachment)));

        verify(immichService).uploadAsset("clip.mp4", videoData, "video/mp4");
        verify(immichService).addAssetsToAlbum("album-123", List.of("asset-vid"));
    }

    @Test
    void doesNotCallAddAssetsWhenAllUploadsFail() {
        Event event = startedEventWithAlbum(99L);
        when(eventRepository.findByChannelId(99L)).thenReturn(event);

        byte[] imageData = new byte[]{1};
        Message.Attachment attachment = imageAttachment("photo.jpg", imageData);
        when(immichService.uploadAsset("photo.jpg", imageData, "image/jpeg")).thenReturn(Optional.empty());

        listener.onMessageReceived(buildEvent(99L, List.of(attachment)));

        verify(immichService).uploadAsset("photo.jpg", imageData, "image/jpeg");
        verify(immichService, never()).addAssetsToAlbum(any(), any());
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.discord.listener.MessageReceivedListenerTest"
```

Expected: FAILED — compilation error because `MessageReceivedListener` does not exist.

- [ ] **Step 3: Create `MessageReceivedListener.java`**

```java
package dev.tylercash.event.discord.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.immich.ImmichConfiguration;
import dev.tylercash.event.immich.ImmichService;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageReceivedListener extends ListenerAdapter {

    private final Clock clock;
    private final EventRepository eventRepository;
    private final ImmichConfiguration immichConfiguration;
    private final ImmichService immichService;

    @Override
    public void onMessageReceived(@NonNull MessageReceivedEvent event) {
        if (!immichConfiguration.isEnabled()) {
            return;
        }
        Message message = event.getMessage();
        List<Message.Attachment> attachments = message.getAttachments();
        if (attachments.isEmpty()) {
            return;
        }
        Event dbEvent = eventRepository.findByChannelId(event.getChannel().getIdLong());
        if (dbEvent == null || dbEvent.getImmichAlbumId() == null) {
            return;
        }
        if (ZonedDateTime.now(clock).isBefore(dbEvent.getDateTime())) {
            return;
        }
        List<String> assetIds = new ArrayList<>();
        for (Message.Attachment attachment : attachments) {
            String contentType = attachment.getContentType();
            if (contentType == null
                    || (!contentType.startsWith("image/") && !contentType.startsWith("video/"))) {
                continue;
            }
            try {
                byte[] data = attachment.getProxy().download().join().readAllBytes();
                immichService.uploadAsset(attachment.getFileName(), data, contentType).ifPresent(assetIds::add);
            } catch (Exception e) {
                log.warn("Failed to download attachment {} from Discord", attachment.getFileName(), e);
            }
        }
        if (!assetIds.isEmpty()) {
            immichService.addAssetsToAlbum(dbEvent.getImmichAlbumId(), assetIds);
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd backend && ./gradlew test --tests "dev.tylercash.event.discord.listener.MessageReceivedListenerTest"
```

Expected: BUILD SUCCESSFUL, all MessageReceivedListenerTest tests pass.

- [ ] **Step 5: Run full test suite**

```bash
cd backend && ./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd backend && ./gradlew spotlessApply
git add backend/src/main/java/dev/tylercash/event/discord/listener/MessageReceivedListener.java \
        backend/src/test/java/dev/tylercash/event/discord/listener/MessageReceivedListenerTest.java
git commit -m "feat(discord): add MessageReceivedListener for Immich photo/video auto-upload"
```

---

## Task 4: Register MessageReceivedListener with JDA

**Files:**
- Modify: `backend/src/main/java/dev/tylercash/event/discord/ClientConfiguration.java`

- [ ] **Step 1: Add the listener to `ClientConfiguration`**

In `ClientConfiguration.java`, add `MessageReceivedListener` as a field (alongside the existing listeners):

```java
private final MessageReceivedListener messageReceivedListener;
```

And register it in the `jda()` bean method — add one line after the existing `addEventListeners` calls:

```java
.addEventListeners(messageReceivedListener)
```

The full updated `jda()` builder chain should look like:

```java
JDA jda = JDABuilder.createDefault(discordConfiguration.getToken())
        .addEventListeners(buttonInteractionListener)
        .addEventListeners(modalInteractionListener)
        .addEventListeners(slashCommandListener)
        .addEventListeners(messageReceivedListener)
        .enableIntents(EnumSet.allOf(GatewayIntent.class))
        .build()
        .awaitReady();
```

No import needed — `MessageReceivedListener` is in the same `listener` package.

- [ ] **Step 2: Run full test suite**

```bash
cd backend && ./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd backend && ./gradlew spotlessApply
git add backend/src/main/java/dev/tylercash/event/discord/ClientConfiguration.java
git commit -m "feat(discord): register MessageReceivedListener with JDA"
```
