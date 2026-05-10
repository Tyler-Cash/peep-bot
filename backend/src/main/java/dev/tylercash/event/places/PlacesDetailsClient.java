package dev.tylercash.event.places;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Resolves a Google Place ID to its lat/lng via Google Places API. Used by
 * the TfNSW integration to cache event coordinates for geo-proximity filtering.
 */
@Slf4j
@Component
public class PlacesDetailsClient {

    public record Coords(double lat, double lng) {}

    private final RestClient placesRestClient;
    private final PlacesConfiguration config;

    public PlacesDetailsClient(RestClient placesRestClient, PlacesConfiguration config) {
        this.placesRestClient = placesRestClient;
        this.config = config;
    }

    public Optional<Coords> fetchCoords(String placeId) {
        if (!config.isEnabled() || placeId == null || placeId.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = placesRestClient
                    .get()
                    .uri("/v1/places/{id}?fields=location&key={key}", placeId, config.getApiKey())
                    .retrieve()
                    .body(JsonNode.class);
            if (node == null || !node.has("location")) {
                return Optional.empty();
            }
            JsonNode loc = node.get("location");
            return Optional.of(new Coords(
                    loc.path("latitude").asDouble(), loc.path("longitude").asDouble()));
        } catch (Exception e) {
            log.warn("Places coords fetch failed for {}: {}", placeId, e.getMessage());
            return Optional.empty();
        }
    }
}
