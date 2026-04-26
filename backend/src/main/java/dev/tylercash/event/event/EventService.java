package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.event.model.*;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import dev.tylercash.event.event.statemachine.EventStateMachineService;
import io.micrometer.observation.annotation.Observed;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {
    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final EventStateMachineService stateMachineService;
    private final Clock clock;
    private final AttendanceService attendanceService;
    private final DiscordUserCacheService discordUserCacheService;

    @Observed(name = "event.create")
    @CacheEvict(value = "activeEvents", allEntries = true)
    @Transactional
    public String createEvent(Event event) {
        log.info("Creating event '{}' by creator {}", event.getName(), event.getCreator());
        eventRepository.save(event);
        MDC.put("eventId", event.getId().toString());
        try {
            stateMachineService.attemptTransition(event, EventStateMachineEvent.INIT_CHANNEL);
        } catch (Exception e) {
            log.warn("Initial setup failed for event '{}', poller will retry", event.getName(), e);
        }
        log.info("Created event '{}' with id={}", event.getName(), event.getId());
        return "Created event for " + event.getName();
    }

    @Cacheable(value = "eventDetail", key = "#id")
    public Event getEvent(UUID id) {
        Optional<Event> event = eventRepository.findById(id);
        if (event.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
        }
        return event.get();
    }

    @Caching(
            evict = {
                @CacheEvict(value = "activeEvents", allEntries = true),
                @CacheEvict(value = "eventDetail", key = "#event.id")
            })
    @Observed(name = "event.update")
    @Transactional
    public Event updateEvent(Event event) {
        MDC.put("eventId", event.getId().toString());
        log.info("Updating event '{}' id={}", event.getName(), event.getId());
        discordService.updateEventMessage(event);
        discordService.updateChannelName(event);
        eventRepository.save(event);
        return event;
    }

    @Cacheable(value = "activeEvents", key = "#guildId + '-' + #pageable.pageNumber + '-' + #pageable.pageSize")
    public Page<Event> getActiveEvents(Pageable pageable, long guildId) {
        return eventRepository.findAllByStateNotInAndServerId(
                pageable, List.of(EventState.CREATED, EventState.ARCHIVED, EventState.DELETED), guildId);
    }

    public boolean isCompleted(Event event) {
        return event.getState().ordinal() >= EventState.POST_COMPLETED.ordinal()
                || ZonedDateTime.now(clock).isAfter(event.getDateTime().plusHours(6));
    }

    @Caching(
            evict = {
                @CacheEvict(value = "activeEvents", allEntries = true),
                @CacheEvict(value = "eventDetail", key = "#id")
            })
    @Observed(name = "event.cancel")
    public void cancelEvent(UUID id) {
        MDC.put("eventId", id.toString());
        log.info("Cancelling event id={}", id);
        Event event = getEvent(id);
        if (isCompleted(event)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event is already completed or cancelled");
        }
        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.CANCEL);
        if (!transitioned) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to cancel event");
        }
    }

    @Caching(
            evict = {
                @CacheEvict(value = "activeEvents", allEntries = true),
                @CacheEvict(value = "eventDetail", key = "#id")
            })
    @Observed(name = "event.remove-attendee")
    @Transactional
    public void removeAttendee(UUID id, String snowflake, String name) {
        MDC.put("eventId", id.toString());
        log.info("Removing attendee from event id={} snowflake={} name={}", id, snowflake, name);
        Event event = getEvent(id);
        if (isCompleted(event)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Attendance is locked for this event");
        }
        attendanceService.removeAttendee(id, snowflake, name);
        populateAttendance(event);
        updateEvent(event);
    }

    @Caching(
            evict = {
                @CacheEvict(value = "activeEvents", allEntries = true),
                @CacheEvict(value = "eventDetail", key = "#id")
            })
    @Observed(name = "event.create-private-channel")
    @Transactional
    public void createPrivateChannel(UUID id) {
        MDC.put("eventId", id.toString());
        log.info("Creating private channel for event id={}", id);
        Event event = getEvent(id);
        discordService.createPrivateEventChannel(event);
        eventRepository.save(event);
    }

    public void populateAttendance(Event event) {
        AttendanceSummary summary = attendanceService.getCurrentAttendance(event.getId());

        Set<String> allSnowflakes = Stream.of(
                        summary.accepted().stream(), summary.declined().stream(), summary.maybe().stream())
                .flatMap(s -> s)
                .map(AttendanceRecord::getSnowflake)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toSet());

        if (event.getCreator() != null && !event.getCreator().isBlank()) {
            allSnowflakes.add(event.getCreator());
        }

        Map<String, String> nameMap = discordUserCacheService.getDisplayNames(allSnowflakes);

        event.setAccepted(toAttendeeSet(summary.accepted(), nameMap));
        event.setDeclined(toAttendeeSet(summary.declined(), nameMap));
        event.setMaybe(toAttendeeSet(summary.maybe(), nameMap));

        String creatorName = nameMap.get(event.getCreator());
        event.setCreatorDisplayName(creatorName != null ? creatorName : event.getCreator());
    }

    private Set<Attendee> toAttendeeSet(List<AttendanceRecord> records, Map<String, String> nameMap) {
        Set<Attendee> attendees = new LinkedHashSet<>();
        for (AttendanceRecord record : records) {
            String displayName;
            if (record.getSnowflake() != null) {
                displayName = nameMap.getOrDefault(
                        record.getSnowflake(), discordUserCacheService.getDisplayName(record.getSnowflake()));
            } else {
                displayName = record.getName();
            }
            attendees.add(Attendee.createWithTimestamp(record.getSnowflake(), displayName, record.getRecordedAt()));
        }
        return attendees;
    }
}
