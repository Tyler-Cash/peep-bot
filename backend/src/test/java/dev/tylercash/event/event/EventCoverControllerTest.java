package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class EventCoverControllerTest {

    @Test
    void returnsImageBytesWhenCoverPresent() {
        EventRepository repo = mock(EventRepository.class);
        UUID id = UUID.randomUUID();
        Event event = new Event();
        event.setId(id);
        event.setCoverImageBytes(new byte[] {1, 2, 3});
        event.setCoverImageContentType("image/jpeg");
        when(repo.findById(id)).thenReturn(Optional.of(event));

        EventCoverController controller = new EventCoverController(repo);
        ResponseEntity<byte[]> response = controller.getCover(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(1, 2, 3);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/jpeg");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("max-age=86400, public");
    }

    @Test
    void returns404WhenNoCover() {
        EventRepository repo = mock(EventRepository.class);
        UUID id = UUID.randomUUID();
        Event event = new Event();
        event.setId(id);
        when(repo.findById(id)).thenReturn(Optional.of(event));

        EventCoverController controller = new EventCoverController(repo);
        assertThat(controller.getCover(id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void returns404WhenEventMissing() {
        EventRepository repo = mock(EventRepository.class);
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        EventCoverController controller = new EventCoverController(repo);
        assertThat(controller.getCover(id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void defaultsToJpegContentTypeWhenMissing() {
        EventRepository repo = mock(EventRepository.class);
        UUID id = UUID.randomUUID();
        Event event = new Event();
        event.setId(id);
        event.setCoverImageBytes(new byte[] {9});
        when(repo.findById(id)).thenReturn(Optional.of(event));

        EventCoverController controller = new EventCoverController(repo);
        assertThat(controller.getCover(id).getHeaders().getContentType().toString())
                .isEqualTo("image/jpeg");
    }
}
