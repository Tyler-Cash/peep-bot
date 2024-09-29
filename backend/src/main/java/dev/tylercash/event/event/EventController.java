package dev.tylercash.event.event;

import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventDto;
import dev.tylercash.event.event.model.EventUpdateDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
    public Map<String, String> updateEvent(@RequestBody @Valid EventUpdateDto eventDto) {
        Event event = eventService.getEvent(eventDto.getId());
        event.setCapacity(eventDto.getCapacity());
        if (Objects.nonNull(eventDto.getDateTime())) {
            event.setDateTime(eventDto.getDateTime());
        }
        if (Objects.nonNull(eventDto.getDescription()) && !eventDto.getDescription().isBlank()) {
            event.setDescription(eventDto.getDescription());
        }
        eventDto.getAccepted()
                .forEach(attendeeName -> {
                    Attendee attendee = Attendee.createDiscordAttendee(null, attendeeName);
                    event.getAccepted().add(attendee);
                });
        eventService.updateEvent(event);
        return Map.of("message", "Updated event for " + event.getName());
    }

    @GetMapping
    public List<EventDto> getEvents(@PageableDefault Pageable pageable) {
        return eventService.getPlannedEvents(pageable).stream().map(EventDto::new).toList();
    }

    @GetMapping(path = "/{id}")
    public EventDto getEvent(@PathVariable UUID id) {
        return new EventDto(eventService.getEvent(id));
    }
}
