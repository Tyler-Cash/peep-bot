package dev.tylercash.event.event;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@AllArgsConstructor
@RequestMapping(value = "/event")
public class EventController {
    private EventService eventService;

    @PutMapping
    public Map<String, String> createEvent(@RequestBody @Valid EventDto event) {
        eventService.createEvent(new Event(event));
        return Map.of("message", "Created event for " + event.getName());
    }

    @PatchMapping
    public Map<String, String> updateEvent(String messageId, LocalDateTime updatedTime) {
        Event event = eventService.getEvent(messageId);
        event.setDateTime(updatedTime);
        eventService.updateEvent(event);
        return Map.of("message", "Updated event for " + event.getName());
    }

    @GetMapping
    public List<Event> getEvents() {
        return eventService.getEvents();
    }

    @DeleteMapping
    public Map<String, String> deleteEvent(String id) {
        return Map.of("message", eventService.deleteEvent(id));
    }
}
