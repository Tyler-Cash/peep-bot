package dev.tylercash.event.event;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.places.PhotoBytes;
import dev.tylercash.event.places.PlacesPhotoClient;
import dev.tylercash.event.places.PlacesRateLimiter;
import dev.tylercash.event.places.StaticMapClient;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoverImageService {
    private static final int MAP_STICKER_SIZE = 260;
    private static final String COMPOSITE_CONTENT_TYPE = "image/png";

    private final PlacesPhotoClient placesPhotoClient;
    private final PlacesRateLimiter placesRateLimiter;
    private final StaticMapClient staticMapClient;
    private final VenueCompositeRenderer compositeRenderer;

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

        if (!placesRateLimiter.tryAcquire(event.getCreator())) {
            log.info("Skipping Places photo fetch for event {} due to rate limit", event.getId());
            return;
        }

        Optional<PhotoBytes> photo = placesPhotoClient.fetchPhoto(placeId);
        if (photo.isEmpty()) {
            log.info("No Places photo available for event {} place_id {}", event.getId(), placeId);
            return;
        }

        Optional<byte[]> mapTile = staticMapClient.fetchStyledMap(placeId, MAP_STICKER_SIZE);
        byte[] composite;
        String contentType;
        try {
            composite = compositeRenderer.render(photo.get().bytes(), mapTile.orElse(null), shortLabel(event));
            contentType = COMPOSITE_CONTENT_TYPE;
        } catch (RuntimeException e) {
            log.warn("Compositor failed for event {}; storing photo only: {}", event.getId(), e.getMessage());
            composite = photo.get().bytes();
            contentType = photo.get().contentType();
        }

        event.setCoverImageBytes(composite);
        event.setCoverImageContentType(contentType);
        event.setCoverImagePlaceId(placeId);
    }

    private static String shortLabel(Event event) {
        String location = event.getLocation();
        if (location == null || location.isBlank()) {
            return "";
        }
        // The address line is often "Name, Street, Suburb, Country". Keep the first 1-2 segments so the
        // ribbon stays readable at Discord's render width — full addresses overflow.
        String[] parts = location.split(",");
        if (parts.length == 1) {
            return parts[0].trim();
        }
        return (parts[0].trim() + " · " + parts[1].trim()).trim();
    }
}
