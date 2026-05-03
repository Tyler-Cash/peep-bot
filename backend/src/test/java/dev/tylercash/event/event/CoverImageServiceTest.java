package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.places.PhotoBytes;
import dev.tylercash.event.places.PlacesPhotoClient;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CoverImageServiceTest {

    @Test
    void fetchesPhotoWhenPlaceIdSetAndCoverMissing() {
        PlacesPhotoClient client = mock(PlacesPhotoClient.class);
        when(client.fetchPhoto("abc")).thenReturn(Optional.of(new PhotoBytes(new byte[] {9, 9}, "image/jpeg")));
        CoverImageService svc = new CoverImageService(client);

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
        CoverImageService svc = new CoverImageService(client);

        Event event = new Event();
        event.setLocationPlaceId("abc");
        event.setCoverImagePlaceId("abc");
        event.setCoverImageBytes(new byte[] {1});
        event.setCoverImageContentType("image/jpeg");

        svc.refreshIfNeeded(event);

        verifyNoInteractions(client);
        assertThat(event.getCoverImageBytes()).containsExactly(1);
    }

    @Test
    void clearsCoverWhenPlaceIdRemoved() {
        PlacesPhotoClient client = mock(PlacesPhotoClient.class);
        CoverImageService svc = new CoverImageService(client);

        Event event = new Event();
        event.setLocationPlaceId(null);
        event.setCoverImagePlaceId("old");
        event.setCoverImageBytes(new byte[] {1});
        event.setCoverImageContentType("image/jpeg");

        svc.refreshIfNeeded(event);

        verifyNoInteractions(client);
        assertThat(event.getCoverImageBytes()).isNull();
        assertThat(event.getCoverImageContentType()).isNull();
        assertThat(event.getCoverImagePlaceId()).isNull();
    }

    @Test
    void leavesEventUnchangedWhenPlacesReturnsEmpty() {
        PlacesPhotoClient client = mock(PlacesPhotoClient.class);
        when(client.fetchPhoto("abc")).thenReturn(Optional.empty());
        CoverImageService svc = new CoverImageService(client);

        Event event = new Event();
        event.setLocationPlaceId("abc");

        svc.refreshIfNeeded(event);

        assertThat(event.getCoverImageBytes()).isNull();
        assertThat(event.getCoverImagePlaceId()).isNull();
    }
}
