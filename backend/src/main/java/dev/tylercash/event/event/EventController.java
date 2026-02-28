package dev.tylercash.event.event;

import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUtil;
import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventDetailDto;
import dev.tylercash.event.event.model.EventDto;
import dev.tylercash.event.event.model.EventUpdateDto;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@AllArgsConstructor
@RequestMapping(value = "/event")
public class EventController {
    private EventService eventService;
    private DiscordService discordService;
    private DiscordConfiguration discordConfiguration;

    @PutMapping
    public Map<String, String> createEvent(@RequestBody @Valid EventDto event,
                                           @AuthenticationPrincipal OAuth2User principal) {
        String creator = principal.getAttribute("username");
        String discordId = principal.getAttribute("id");
        Event newEvent = new Event(event, creator);
        Member member = discordService.getMemberFromServer(discordConfiguration.getGuildId(), Long.parseLong(discordId));
        String displayName = DiscordUtil.getUserDisplayName(member);
        newEvent.getAccepted().add(Attendee.createDiscordAttendee(discordId, displayName));
        eventService.createEvent(newEvent);
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
        if (Objects.nonNull(eventDto.getName()) && !eventDto.getName().isBlank()) {
            event.setName(eventDto.getName());
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
    public EventDetailDto getEvent(@PathVariable UUID id) {
        return new EventDetailDto(eventService.getEvent(id));
    }

    @DeleteMapping(path = "/{id}/attendee")
    public Map<String, String> removeAttendee(
            @PathVariable UUID id,
            @RequestParam(required = false) String snowflake,
            @RequestParam(required = false) String name,
            @AuthenticationPrincipal OAuth2User principal) {
        String discordId = principal.getAttribute("id");
        if (!discordService.isUserAdminOfServer(discordConfiguration.getGuildId(), Long.parseLong(discordId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required to remove attendees");
        }
        eventService.removeAttendee(id, snowflake, name);
        return Map.of("message", "Removed attendee");
    }
}
