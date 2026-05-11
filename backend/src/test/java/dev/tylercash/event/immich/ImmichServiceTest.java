package dev.tylercash.event.immich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class ImmichServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ImmichConfiguration enabledConfig() {
        ImmichConfiguration config = new ImmichConfiguration();
        config.setEnabled(true);
        config.setBaseUrl("https://immich.example.com");
        config.setApiKey("test-api-key");
        return config;
    }

    private ImmichConfiguration disabledConfig() {
        ImmichConfiguration config = new ImmichConfiguration();
        config.setEnabled(false);
        return config;
    }

    private Retry noWaitRetry() {
        return Retry.of(
                "test",
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ZERO)
                        .retryOnException(
                                e -> e instanceof RestClientException && !(e instanceof HttpClientErrorException))
                        .build());
    }

    @Test
    @DisplayName("Successful album creation returns ID")
    void createAlbumSuccess() throws JsonProcessingException {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://immich.example.com/api/albums"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        objectMapper.writeValueAsString(new ImmichAlbumResponse("album-123", "Test Event", null, 0)),
                        MediaType.APPLICATION_JSON));

        ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
        Optional<String> result = service.createAlbum("Test Event", "Description");

        assertTrue(result.isPresent());
        assertEquals("album-123", result.get());
        server.verify();
    }

    @Test
    @DisplayName("Successful shared link creation returns key")
    void createSharedLinkSuccess() throws JsonProcessingException {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://immich.example.com/api/shared-links"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        objectMapper.writeValueAsString(new ImmichSharedLinkResponse("share-key-456")),
                        MediaType.APPLICATION_JSON));

        ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
        Optional<String> result = service.createSharedLink("album-123");

        assertTrue(result.isPresent());
        assertEquals("share-key-456", result.get());
        server.verify();
    }

    @Test
    @DisplayName("API error returns empty Optional")
    void createAlbumApiError() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://immich.example.com/api/albums"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
        Optional<String> result = service.createAlbum("Test Event", "Description");

        assertTrue(result.isEmpty());
        server.verify();
    }

    @Test
    @DisplayName("Disabled config returns empty Optional without HTTP calls")
    void disabledConfigReturnsEmpty() {
        RestClient restClient = RestClient.builder().build();
        ImmichService service = new ImmichService(disabledConfig(), restClient, noWaitRetry());

        assertTrue(service.createAlbum("Test", "Desc").isEmpty());
        assertTrue(service.createSharedLink("album-id").isEmpty());
    }

    @Test
    @DisplayName("Album name includes prefix when configured")
    void createAlbumWithPrefix() throws JsonProcessingException {
        ImmichConfiguration config = enabledConfig();
        config.setAlbumNamePrefix("[DEV] ");

        RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://immich.example.com/api/albums"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"albumName\":\"[DEV] Test Event\"}"))
                .andRespond(withSuccess(
                        objectMapper.writeValueAsString(new ImmichAlbumResponse("album-123", "Test Event", null, 0)),
                        MediaType.APPLICATION_JSON));

        ImmichService service = new ImmichService(config, builder.build(), noWaitRetry());
        Optional<String> result = service.createAlbum("Test Event", "Description");

        assertTrue(result.isPresent());
        assertEquals("album-123", result.get());
        server.verify();
    }

    @Test
    @DisplayName("getShareUrl constructs correct URL")
    void getShareUrlConstructsCorrectUrl() {
        ImmichService service =
                new ImmichService(enabledConfig(), RestClient.builder().build(), noWaitRetry());
        assertEquals("https://immich.example.com/share/my-key", service.getShareUrl("my-key"));
    }

    @Test
    @DisplayName("uploadAsset: success returns asset ID")
    void uploadAssetSuccess() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://immich.example.com/api/assets"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"asset-abc\"}", MediaType.APPLICATION_JSON));

        ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
        Optional<String> result = service.uploadAsset("photo.jpg", new byte[] {1, 2, 3}, "image/jpeg");

        assertTrue(result.isPresent());
        assertEquals("asset-abc", result.get());
        server.verify();
    }

    @Test
    @DisplayName("uploadAsset: disabled returns empty without HTTP calls")
    void uploadAssetDisabledReturnsEmpty() {
        ImmichService service =
                new ImmichService(disabledConfig(), RestClient.builder().build(), noWaitRetry());
        assertTrue(
                service.uploadAsset("photo.jpg", new byte[] {1}, "image/jpeg").isEmpty());
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
        Optional<String> result = service.uploadAsset("photo.jpg", new byte[] {1, 2, 3}, "image/jpeg");

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
        Optional<String> result = service.uploadAsset("photo.jpg", new byte[] {1, 2, 3}, "image/jpeg");

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
        ImmichService service =
                new ImmichService(disabledConfig(), RestClient.builder().build(), noWaitRetry());
        service.addAssetsToAlbum("album-123", List.of("asset-abc")); // no exception = pass
    }

    // -------------------------------------------------------------------------
    // streamThumbnail
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("streamThumbnail: 2xx response copies body to output stream and returns true")
    void streamThumbnailSuccess() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        byte[] thumbnailBytes = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}; // jpeg magic prefix
        server.expect(requestTo("https://immich.example.com/api/assets/asset-xyz/thumbnail?size=preview"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(thumbnailBytes, MediaType.IMAGE_JPEG));

        ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        boolean ok = service.streamThumbnail("asset-xyz", out);

        assertTrue(ok);
        assertEquals(3, out.size());
        assertEquals((byte) 0xFF, out.toByteArray()[0]);
        server.verify();
    }

    @Test
    @DisplayName("streamThumbnail: disabled config returns false without HTTP calls")
    void streamThumbnailDisabledReturnsFalse() {
        ImmichService service =
                new ImmichService(disabledConfig(), RestClient.builder().build(), noWaitRetry());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertTrue(!service.streamThumbnail("asset-xyz", out));
        assertEquals(0, out.size());
    }

    @Test
    @DisplayName("streamThumbnail: server error returns false")
    void streamThumbnailServerErrorReturnsFalse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://immich.example.com/api/assets/asset-xyz/thumbnail?size=preview"))
                .andRespond(withServerError());

        ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        boolean ok = service.streamThumbnail("asset-xyz", out);

        assertTrue(!ok);
        server.verify();
    }

    // -------------------------------------------------------------------------
    // getAlbumDetails
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getAlbumDetails: 200 response returns populated Optional")
    void getAlbumDetailsSuccess() throws JsonProcessingException {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ImmichAlbumResponse expected = new ImmichAlbumResponse("album-xyz", "My Album", "thumb-asset-id", 7);
        server.expect(requestTo("https://immich.example.com/api/albums/album-xyz?withoutAssets=true"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(expected), MediaType.APPLICATION_JSON));

        ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
        Optional<ImmichAlbumResponse> result = service.getAlbumDetails("album-xyz");

        assertTrue(result.isPresent());
        assertEquals("album-xyz", result.get().id());
        assertEquals("My Album", result.get().albumName());
        assertEquals("thumb-asset-id", result.get().albumThumbnailAssetId());
        assertEquals(7, result.get().assetCount());
        server.verify();
    }

    @Test
    @DisplayName("getAlbumDetails: disabled config returns empty without HTTP calls")
    void getAlbumDetailsDisabledReturnsEmpty() {
        ImmichService service =
                new ImmichService(disabledConfig(), RestClient.builder().build(), noWaitRetry());
        assertTrue(service.getAlbumDetails("album-xyz").isEmpty());
    }

    @Test
    @DisplayName("getAlbumDetails: server error returns empty Optional")
    void getAlbumDetailsServerErrorReturnsEmpty() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://immich.example.com/api/albums/album-xyz?withoutAssets=true"))
                .andRespond(withServerError());

        ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
        Optional<ImmichAlbumResponse> result = service.getAlbumDetails("album-xyz");

        assertTrue(result.isEmpty());
        server.verify();
    }

    // -------------------------------------------------------------------------
    // createSharedLink with description + expiry
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createSharedLink with description+expiry: body includes both fields")
    void createSharedLinkWithDescriptionAndExpiry() throws JsonProcessingException {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        java.time.Instant expiry = java.time.Instant.parse("2030-01-01T00:00:00Z");
        server.expect(requestTo("https://immich.example.com/api/shared-links"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content()
                        .json("{\"type\":\"ALBUM\",\"albumId\":\"album-xyz\",\"allowUpload\":true,"
                                + "\"allowDownload\":true,\"showMetadata\":true,"
                                + "\"description\":\"Alice — 2030\","
                                + "\"expiresAt\":\"2030-01-01T00:00:00Z\"}"))
                .andRespond(withSuccess(
                        objectMapper.writeValueAsString(new ImmichSharedLinkResponse("key-xyz")),
                        MediaType.APPLICATION_JSON));

        ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
        Optional<String> result = service.createSharedLink("album-xyz", "Alice — 2030", expiry);

        assertTrue(result.isPresent());
        assertEquals("key-xyz", result.get());
        server.verify();
    }

    @Test
    @DisplayName("createSharedLink with blank description: body omits description field")
    void createSharedLinkBlankDescriptionOmitsField() throws JsonProcessingException {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://immich.example.com/api/shared-links"))
                .andExpect(method(HttpMethod.POST))
                // Body should NOT contain "description" — only the always-present fields.
                .andExpect(content().json("{\"type\":\"ALBUM\",\"albumId\":\"album-xyz\"}"))
                .andRespond(withSuccess(
                        objectMapper.writeValueAsString(new ImmichSharedLinkResponse("k")),
                        MediaType.APPLICATION_JSON));

        ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
        Optional<String> result = service.createSharedLink("album-xyz", "   ", null);

        assertTrue(result.isPresent());
        server.verify();
    }

    @Test
    @DisplayName("createSharedLink: null body response returns empty Optional")
    void createSharedLinkNullBodyReturnsEmpty() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 204 No Content → null body deserialised
        server.expect(requestTo("https://immich.example.com/api/shared-links")).andRespond(withSuccess());

        ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
        Optional<String> result = service.createSharedLink("album-xyz");

        assertTrue(result.isEmpty());
        server.verify();
    }

    @Test
    @DisplayName("addAssetsToAlbum: server error after retries does not throw")
    void addAssetsToAlbumServerErrorDoesNotThrow() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://immich.example.com/api/albums/album-123/assets"))
                .andRespond(withServerError());
        server.expect(requestTo("https://immich.example.com/api/albums/album-123/assets"))
                .andRespond(withServerError());
        server.expect(requestTo("https://immich.example.com/api/albums/album-123/assets"))
                .andRespond(withServerError());

        ImmichService service = new ImmichService(enabledConfig(), builder.build(), noWaitRetry());
        service.addAssetsToAlbum("album-123", List.of("asset-abc")); // must not throw

        server.verify();
    }
}
