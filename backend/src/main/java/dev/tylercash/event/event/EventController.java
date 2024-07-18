package dev.tylercash.event.event;

import dev.tylercash.event.db.model.Event;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@AllArgsConstructor
@RequestMapping(value = "/event")
public class EventController {
    private EventService eventService;

    @PutMapping
    public String createEvent(Event event) {
        eventService.createEvent(event);
        return "Created event for " + event.getName();
    }

    @PatchMapping
    public String updateEvent(String messageId, LocalDateTime updatedTime) {
        Event event = eventService.getEvent(messageId);
        event.setDateTime(updatedTime);
        eventService.updateEvent(event);
        return "Updated event for " + event.getName();
    }

    @GetMapping
    public List<Event> getEvents() {
        return eventService.getEvents();
    }
}
