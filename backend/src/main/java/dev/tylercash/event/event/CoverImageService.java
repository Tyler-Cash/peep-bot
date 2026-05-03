package dev.tylercash.event.event;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.places.PhotoBytes;
import dev.tylercash.event.places.PlacesPhotoClient;
import dev.tylercash.event.places.PlacesRateLimiter;
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
    private final PlacesRateLimiter placesRateLimiter;

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
        event.setCoverImageBytes(photo.get().bytes());
        event.setCoverImageContentType(photo.get().contentType());
        event.setCoverImagePlaceId(placeId);
    }
}
