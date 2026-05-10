package dev.tylercash.event.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tylercash.event.places.PlacesDetailsClient.Coords;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PlacesDetailsClientTest {

    @Test
    void parsesLatLngFromGooglePlacesResponse() throws Exception {
        JsonNode node = new ObjectMapper()
                .readTree(
                        """
                {"location": {"latitude": -33.8688, "longitude": 151.2093}}
                """);

        RestClient client = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(client.get().uri(anyString(), anyString(), anyString()).retrieve().body(eq(JsonNode.class)))
                .thenReturn(node);

        PlacesConfiguration cfg = new PlacesConfiguration();
        cfg.setApiKey("k");
        Optional<Coords> out = new PlacesDetailsClient(client, cfg).fetchCoords("place-id");

        assertThat(out).isPresent();
        assertThat(out.get().lat()).isEqualTo(-33.8688);
        assertThat(out.get().lng()).isEqualTo(151.2093);
    }

    @Test
    void returnsEmptyWhenDisabled() {
        PlacesConfiguration cfg = new PlacesConfiguration(); // no key
        assertThat(new PlacesDetailsClient(mock(RestClient.class), cfg).fetchCoords("x"))
                .isEmpty();
    }

    @Test
    void returnsEmptyWhenPlaceIdBlank() {
        PlacesConfiguration cfg = new PlacesConfiguration();
        cfg.setApiKey("k");
        assertThat(new PlacesDetailsClient(mock(RestClient.class), cfg).fetchCoords("  "))
                .isEmpty();
    }
}
