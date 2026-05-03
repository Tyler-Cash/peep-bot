package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventCoverController {
    private final EventRepository eventRepository;

    @GetMapping("/{id}/cover")
    public ResponseEntity<byte[]> getCover(@PathVariable UUID id) {
        return eventRepository
                .findById(id)
                .filter(e -> e.getCoverImageBytes() != null && e.getCoverImageBytes().length > 0)
                .map(this::toResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<byte[]> toResponse(Event event) {
        MediaType type = event.getCoverImageContentType() != null
                ? MediaType.parseMediaType(event.getCoverImageContentType())
                : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(event.getCoverImageBytes());
    }
}
