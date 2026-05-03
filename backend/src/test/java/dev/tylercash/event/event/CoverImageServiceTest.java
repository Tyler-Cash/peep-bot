package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.places.PhotoBytes;
import dev.tylercash.event.places.PlacesPhotoClient;
import dev.tylercash.event.places.PlacesRateLimiter;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CoverImageServiceTest {

    private static PlacesRateLimiter allowingLimiter() {
        PlacesRateLimiter limiter = mock(PlacesRateLimiter.class);
        when(limiter.tryAcquire(any())).thenReturn(true);
        return limiter;
    }

    @Test
    void fetchesPhotoWhenPlaceIdSetAndCoverMissing() {
        PlacesPhotoClient client = mock(PlacesPhotoClient.class);
        when(client.fetchPhoto("abc")).thenReturn(Optional.of(new PhotoBytes(new byte[] {9, 9}, "image/jpeg")));
        CoverImageService svc = new CoverImageService(client, allowingLimiter());

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
        PlacesRateLimiter limiter = mock(PlacesRateLimiter.class);
        CoverImageService svc = new CoverImageService(client, limiter);

        Event event = new Event();
        event.setLocationPlaceId("abc");
        event.setCoverImagePlaceId("abc");
        event.setCoverImageBytes(new byte[] {1});
        event.setCoverImageContentType("image/jpeg");

        svc.refreshIfNeeded(event);

        verifyNoInteractions(client);
        verifyNoInteractions(limiter);
        assertThat(event.getCoverImageBytes()).containsExactly(1);
    }

    @Test
    void clearsCoverWhenPlaceIdRemoved() {
        PlacesPhotoClient client = mock(PlacesPhotoClient.class);
        PlacesRateLimiter limiter = mock(PlacesRateLimiter.class);
        CoverImageService svc = new CoverImageService(client, limiter);

        Event event = new Event();
        event.setLocationPlaceId(null);
        event.setCoverImagePlaceId("old");
        event.setCoverImageBytes(new byte[] {1});
        event.setCoverImageContentType("image/jpeg");

        svc.refreshIfNeeded(event);

        verifyNoInteractions(client);
        verifyNoInteractions(limiter);
        assertThat(event.getCoverImageBytes()).isNull();
        assertThat(event.getCoverImageContentType()).isNull();
        assertThat(event.getCoverImagePlaceId()).isNull();
    }

    @Test
    void leavesEventUnchangedWhenPlacesReturnsEmpty() {
        PlacesPhotoClient client = mock(PlacesPhotoClient.class);
        when(client.fetchPhoto("abc")).thenReturn(Optional.empty());
        CoverImageService svc = new CoverImageService(client, allowingLimiter());

        Event event = new Event();
        event.setLocationPlaceId("abc");

        svc.refreshIfNeeded(event);

        assertThat(event.getCoverImageBytes()).isNull();
        assertThat(event.getCoverImagePlaceId()).isNull();
    }

    @Test
    void skipsFetchWhenRateLimited() {
        PlacesPhotoClient client = mock(PlacesPhotoClient.class);
        PlacesRateLimiter limiter = mock(PlacesRateLimiter.class);
        when(limiter.tryAcquire(any())).thenReturn(false);
        CoverImageService svc = new CoverImageService(client, limiter);

        Event event = new Event();
        event.setLocationPlaceId("abc");
        event.setCreator("user-123");

        svc.refreshIfNeeded(event);

        verify(limiter).tryAcquire("user-123");
        verifyNoInteractions(client);
        assertThat(event.getCoverImageBytes()).isNull();
        assertThat(event.getCoverImagePlaceId()).isNull();
    }
}
