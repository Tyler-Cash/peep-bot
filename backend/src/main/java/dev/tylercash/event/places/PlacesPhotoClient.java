package dev.tylercash.event.places;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Slf4j
@Component
public class PlacesPhotoClient {
    private final RestClient placesRestClient;
    private final PlacesConfiguration config;

    public PlacesPhotoClient(RestClient placesRestClient, PlacesConfiguration config) {
        this.placesRestClient = placesRestClient;
        this.config = config;
    }

    public Optional<PhotoBytes> fetchPhoto(String placeId) {
        if (!config.isEnabled()) {
            log.debug("Places API key not configured; skipping photo fetch for place {}", placeId);
            return Optional.empty();
        }
        if (placeId == null || placeId.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode place = placesRestClient
                    .get()
                    .uri("/v1/places/{id}?fields=photos&key={key}", placeId, config.getApiKey())
                    .retrieve()
                    .body(JsonNode.class);
            if (place == null
                    || !place.hasNonNull("photos")
                    || place.get("photos").isEmpty()) {
                return Optional.empty();
            }
            String photoName = place.get("photos").get(0).get("name").asText();
            ResponseEntity<byte[]> media = placesRestClient
                    .get()
                    .uri(builder -> builder.path("/v1/" + photoName + "/media")
                            .queryParam("maxHeightPx", config.getMaxHeightPx())
                            .queryParam("key", config.getApiKey())
                            .build())
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
