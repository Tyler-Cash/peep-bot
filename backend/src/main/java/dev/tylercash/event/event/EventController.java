package dev.tylercash.event.event;

import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUtil;
import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventDetailDto;
import dev.tylercash.event.event.model.EventDto;
import dev.tylercash.event.event.model.EventUpdateDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@AllArgsConstructor
@RequestMapping(value = "/event")
@Tag(name = "Events", description = "Event management operations")
public class EventController {
    private EventService eventService;
    private DiscordService discordService;
    private DiscordConfiguration discordConfiguration;

    @Operation(summary = "Create a new event", description = "Creates an event and its associated Discord channel")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event created successfully"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
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

    @Operation(summary = "Update an existing event", description = "Partially updates event details and syncs with Discord")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event updated successfully"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "404", description = "Event not found")
    })
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

    @Operation(summary = "List active events", description = "Returns paginated list of non-archived, non-deleted events")
    @ApiResponse(responseCode = "200", description = "Events retrieved successfully")
    @GetMapping
    public Page<EventDto> getEvents(@PageableDefault Pageable pageable) {
        return eventService.getActiveEvents(pageable).map(EventDto::new);
    }

    @Operation(summary = "Get event details", description = "Returns full event details including attendee lists")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event details retrieved"),
        @ApiResponse(responseCode = "404", description = "Event not found")
    })
    @GetMapping(path = "/{id}")
    public EventDetailDto getEvent(@PathVariable UUID id) {
        Event event = eventService.getEvent(id);
        return new EventDetailDto(event, eventService.isCompleted(event));
    }

    @Operation(summary = "Cancel an event", description = "Admin-only: cancels an event and archives it")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event cancelled"),
        @ApiResponse(responseCode = "400", description = "Event already completed"),
        @ApiResponse(responseCode = "403", description = "Admin role required"),
        @ApiResponse(responseCode = "404", description = "Event not found")
    })
    @PostMapping(path = "/{id}/cancel")
    public Map<String, String> cancelEvent(
            @PathVariable UUID id,
            @AuthenticationPrincipal OAuth2User principal) {
        String discordId = principal.getAttribute("id");
        if (!discordService.isUserAdminOfServer(discordConfiguration.getGuildId(), Long.parseLong(discordId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
        eventService.cancelEvent(id);
        return Map.of("message", "Event cancelled");
    }

    @Operation(summary = "Remove an attendee", description = "Admin-only: removes an attendee from all RSVP lists")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Attendee removed"),
        @ApiResponse(responseCode = "403", description = "Admin role required"),
        @ApiResponse(responseCode = "404", description = "Event not found")
    })
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
