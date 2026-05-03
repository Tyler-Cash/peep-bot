package dev.tylercash.event.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PlacesPhotoClientTest {

    private PlacesConfiguration enabledConfig() {
        PlacesConfiguration c = new PlacesConfiguration();
        c.setApiKey("fake-key");
        c.setMaxHeightPx(720);
        return c;
    }

    @Test
    void fetchesPhotoBytesForPlaceId() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://places.googleapis.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://places.googleapis.com/v1/places/abc?fields=photos&key=fake-key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"photos\":[{\"name\":\"places/abc/photos/PHOTO_REF\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://places.googleapis.com/v1/places/abc/photos/PHOTO_REF/media?maxHeightPx=720&key=fake-key"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(new byte[] {1, 2, 3}, MediaType.IMAGE_JPEG));

        PlacesPhotoClient client = new PlacesPhotoClient(builder.build(), enabledConfig());

        Optional<PhotoBytes> result = client.fetchPhoto("abc");

        assertThat(result).isPresent();
        assertThat(result.get().contentType()).isEqualTo("image/jpeg");
        assertThat(result.get().bytes()).containsExactly(1, 2, 3);
        server.verify();
    }

    @Test
    void returnsEmptyWhenPlaceHasNoPhotos() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://places.googleapis.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://places.googleapis.com/v1/places/empty?fields=photos&key=fake-key"))
                .andRespond(withSuccess("{\"photos\":[]}", MediaType.APPLICATION_JSON));

        PlacesPhotoClient client = new PlacesPhotoClient(builder.build(), enabledConfig());

        assertThat(client.fetchPhoto("empty")).isEmpty();
        server.verify();
    }

    @Test
    void returnsEmptyOnHttpError() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://places.googleapis.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://places.googleapis.com/v1/places/bad?fields=photos&key=fake-key"))
                .andRespond(withServerError());

        PlacesPhotoClient client = new PlacesPhotoClient(builder.build(), enabledConfig());

        assertThat(client.fetchPhoto("bad")).isEmpty();
        server.verify();
    }

    @Test
    void disabledConfigSkipsHttp() {
        PlacesConfiguration disabled = new PlacesConfiguration();
        disabled.setApiKey("");

        PlacesPhotoClient client =
                new PlacesPhotoClient(RestClient.builder().build(), disabled);

        assertThat(client.fetchPhoto("abc")).isEmpty();
    }
}
