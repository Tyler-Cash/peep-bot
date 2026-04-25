package dev.tylercash.event.event;

import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.discord.DiscordUtil;
import dev.tylercash.event.event.model.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping(value = "/event")
@Tag(name = "Events", description = "Event management operations")
public class EventController {
    private EventService eventService;
    private DiscordService discordService;
    private DiscordConfiguration discordConfiguration;
    private AttendanceService attendanceService;
    private DiscordUserCacheService discordUserCacheService;

    @Operation(summary = "Create a new event", description = "Creates an event and its associated Discord channel")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event created successfully"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PutMapping
    public Map<String, String> createEvent(
            @RequestBody @Valid EventDto event, @AuthenticationPrincipal OAuth2User principal) {
        String discordId = principal.getAttribute("id");
        log.info("User {} creating event '{}'", discordId, event.getName());
        Member member =
                discordService.getMemberFromServer(discordConfiguration.getGuildId(), Long.parseLong(discordId));
        String displayName = DiscordUtil.getUserDisplayName(member);

        Event newEvent = new Event(event, discordId);
        discordUserCacheService.upsertUser(discordId, displayName, null);
        newEvent.getAccepted().add(Attendee.createDiscordAttendee(discordId, displayName));

        eventService.createEvent(newEvent);

        attendanceService.recordAttendance(newEvent.getId(), discordId, null, AttendanceStatus.ACCEPTED, null);

        return Map.of("message", "Created event for " + event.getName());
    }

    @Operation(
            summary = "Update an existing event",
            description = "Partially updates event details and syncs with Discord")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event updated successfully"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "Not authenticated"),
        @ApiResponse(responseCode = "404", description = "Event not found")
    })
    @PatchMapping
    public Map<String, String> updateEvent(
            @RequestBody @Valid EventUpdateDto eventDto, @AuthenticationPrincipal OAuth2User principal) {
        String adminDiscordIdForLog = principal.getAttribute("id");
        log.info("User {} updating event id={}", adminDiscordIdForLog, eventDto.getId());
        Event event = eventService.getEvent(eventDto.getId());
        event.setCapacity(eventDto.getCapacity());
        if (Objects.nonNull(eventDto.getDateTime())) {
            event.setDateTime(eventDto.getDateTime());
        }
        if (Objects.nonNull(eventDto.getDescription())
                && !eventDto.getDescription().isBlank()) {
            event.setDescription(eventDto.getDescription());
        }
        if (Objects.nonNull(eventDto.getName()) && !eventDto.getName().isBlank()) {
            event.setName(eventDto.getName());
        }
        if (Objects.nonNull(eventDto.getLocation())) {
            event.setLocation(eventDto.getLocation());
        }
        String adminDiscordId = principal.getAttribute("id");
        eventDto.getAccepted()
                .forEach(attendeeName -> attendanceService.recordAttendance(
                        event.getId(), null, "[+1] " + attendeeName, AttendanceStatus.ACCEPTED, adminDiscordId));
        eventService.populateAttendance(event);
        eventService.updateEvent(event);
        return Map.of("message", "Updated event for " + event.getName());
    }

    @Operation(
            summary = "List active events",
            description = "Returns paginated list of non-archived, non-deleted events")
    @ApiResponse(responseCode = "200", description = "Events retrieved successfully")
    @GetMapping
    public Page<EventDto> getEvents(@PageableDefault Pageable pageable) {
        Page<Event> events = eventService.getActiveEvents(pageable);
        Set<String> creatorSnowflakes = events.stream()
                .map(Event::getCreator)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> nameMap = discordUserCacheService.getDisplayNames(creatorSnowflakes);
        return events.map(event -> new EventDto(event, nameMap.getOrDefault(event.getCreator(), event.getCreator())));
    }

    @Operation(summary = "Get event details", description = "Returns full event details including attendee lists")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event details retrieved"),
        @ApiResponse(responseCode = "404", description = "Event not found")
    })
    @GetMapping(path = "/{id}")
    public EventDetailDto getEvent(@PathVariable UUID id) {
        Event event = eventService.getEvent(id);
        boolean completed = eventService.isCompleted(event);
        AttendanceSummary summary = attendanceService.getCurrentAttendance(id);

        Set<String> allSnowflakes = Stream.of(
                        summary.accepted().stream(), summary.declined().stream(), summary.maybe().stream())
                .flatMap(s -> s)
                .map(AttendanceRecord::getSnowflake)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toCollection(HashSet::new));

        // Include creator so EventDetailDto can resolve the host name
        if (event.getCreator() != null && !event.getCreator().isBlank()) {
            allSnowflakes.add(event.getCreator());
        }

        Map<String, String> nameMap = discordUserCacheService.getDisplayNames(allSnowflakes);
        return new EventDetailDto(event, completed, summary, nameMap);
    }

    @Operation(summary = "Cancel an event", description = "Admin-only: cancels an event and archives it")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Event cancelled"),
        @ApiResponse(responseCode = "400", description = "Event already completed"),
        @ApiResponse(responseCode = "403", description = "Admin role required"),
        @ApiResponse(responseCode = "404", description = "Event not found")
    })
    @PostMapping(path = "/{id}/cancel")
    public Map<String, String> cancelEvent(@PathVariable UUID id, @AuthenticationPrincipal OAuth2User principal) {
        String discordId = principal.getAttribute("id");
        log.info("User {} cancelling event id={}", discordId, id);
        if (!discordService.isUserAdminOfServer(discordConfiguration.getGuildId(), Long.parseLong(discordId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
        eventService.cancelEvent(id);
        return Map.of("message", "Event cancelled");
    }

    @Operation(
            summary = "Create a private channel",
            description = "Admin-only: creates a private Discord channel for accepted attendees")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Private channel created"),
        @ApiResponse(responseCode = "403", description = "Admin role required"),
        @ApiResponse(responseCode = "404", description = "Event not found")
    })
    @PostMapping(path = "/{id}/private-channel")
    public Map<String, String> createPrivateChannel(
            @PathVariable UUID id, @AuthenticationPrincipal OAuth2User principal) {
        String discordId = principal.getAttribute("id");
        log.info("User {} creating private channel for event id={}", discordId, id);
        if (!discordService.isUserAdminOfServer(discordConfiguration.getGuildId(), Long.parseLong(discordId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
        eventService.createPrivateChannel(id);
        return Map.of("message", "Private channel created");
    }

    @Operation(
            summary = "Remove an attendee",
            description =
                    "Removes an attendee from the event. Admins can remove anyone; non-admins can only remove their own +1s.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Attendee removed"),
        @ApiResponse(responseCode = "403", description = "Not authorized to remove this attendee"),
        @ApiResponse(responseCode = "404", description = "Event not found")
    })
    @DeleteMapping(path = "/{id}/attendee")
    public Map<String, String> removeAttendee(
            @PathVariable UUID id,
            @RequestParam(required = false) String snowflake,
            @RequestParam(required = false) String name,
            @AuthenticationPrincipal OAuth2User principal) {
        String discordId = principal.getAttribute("id");
        log.info("User {} removing attendee from event id={} snowflake={} name={}", discordId, id, snowflake, name);
        boolean isAdmin =
                discordService.isUserAdminOfServer(discordConfiguration.getGuildId(), Long.parseLong(discordId));

        if (!isAdmin) {
            if (snowflake != null && !snowflake.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Only admins can remove Discord-authenticated attendees");
            }
            if (name == null || !attendanceService.isOwnerOfPlusOne(id, name, discordId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only remove +1 guests that you added");
            }
        }

        eventService.removeAttendee(id, snowflake, name);
        return Map.of("message", "Removed attendee");
    }
}
