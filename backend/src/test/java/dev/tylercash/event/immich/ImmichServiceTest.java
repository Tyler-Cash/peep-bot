package dev.tylercash.event.immich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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

    @Test
    @DisplayName("Successful album creation returns ID")
    void createAlbumSuccess() throws JsonProcessingException {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://immich.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://immich.example.com/api/albums"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        objectMapper.writeValueAsString(new ImmichAlbumResponse("album-123")),
                        MediaType.APPLICATION_JSON));

        ImmichService service = new ImmichService(enabledConfig(), builder.build());
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

        ImmichService service = new ImmichService(enabledConfig(), builder.build());
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

        ImmichService service = new ImmichService(enabledConfig(), builder.build());
        Optional<String> result = service.createAlbum("Test Event", "Description");

        assertTrue(result.isEmpty());
        server.verify();
    }

    @Test
    @DisplayName("Disabled config returns empty Optional without HTTP calls")
    void disabledConfigReturnsEmpty() {
        RestClient restClient = RestClient.builder().build();
        ImmichService service = new ImmichService(disabledConfig(), restClient);

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
                        objectMapper.writeValueAsString(new ImmichAlbumResponse("album-123")),
                        MediaType.APPLICATION_JSON));

        ImmichService service = new ImmichService(config, builder.build());
        Optional<String> result = service.createAlbum("Test Event", "Description");

        assertTrue(result.isPresent());
        assertEquals("album-123", result.get());
        server.verify();
    }

    @Test
    @DisplayName("getShareUrl constructs correct URL")
    void getShareUrlConstructsCorrectUrl() {
        ImmichService service =
                new ImmichService(enabledConfig(), RestClient.builder().build());
        assertEquals("https://immich.example.com/share/my-key", service.getShareUrl("my-key"));
    }
}
