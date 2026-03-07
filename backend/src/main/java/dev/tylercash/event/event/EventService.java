package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.event.model.*;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import dev.tylercash.event.event.statemachine.EventStateMachineService;
import dev.tylercash.event.immich.ImmichService;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Log4j2
@Service
@RequiredArgsConstructor
public class EventService {
    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final ImmichService immichService;
    private final EventStateMachineService stateMachineService;
    private final Clock clock;
    private final AttendanceService attendanceService;
    private final DiscordUserCacheService discordUserCacheService;

    @CacheEvict(value = "activeEvents", allEntries = true)
    public String createEvent(Event event) {
        TextChannel channel = discordService.createEventChannel(event);
        try {
            Message message = discordService.postEventMessage(event, channel);
            event.setServerId(message.getGuildIdLong());
            event.setChannelId(channel.getIdLong());
            event.setMessageId(message.getIdLong());
            eventRepository.save(event);
        } catch (Exception e) {
            channel.delete().queue();
            throw e;
        }
        immichService.createAlbum(event.getName(), event.getDescription()).ifPresent(albumId -> {
            event.setImmichAlbumId(albumId);
            immichService.createSharedLink(albumId).ifPresent(event::setImmichShareKey);
            eventRepository.save(event);
        });
        discordService.sortActiveChannels();
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
    @Transactional
    public Event updateEvent(Event event) {
        discordService.updateEventMessage(event);
        discordService.updateChannelName(event);
        eventRepository.save(event);
        return event;
    }

    @Cacheable("activeEvents")
    public Page<Event> getActiveEvents(Pageable pageable) {
        return eventRepository.findAllByStateNotIn(pageable, List.of(EventState.ARCHIVED, EventState.DELETED));
    }

    public boolean isCompleted(Event event) {
        return event.getState().ordinal() >= EventState.COMPLETED.ordinal()
                || ZonedDateTime.now(clock).isAfter(event.getDateTime().plusHours(6));
    }

    @Caching(
            evict = {
                @CacheEvict(value = "activeEvents", allEntries = true),
                @CacheEvict(value = "eventDetail", key = "#id")
            })
    public void cancelEvent(UUID id) {
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
    @Transactional
    public void removeAttendee(UUID id, String snowflake, String name) {
        Event event = getEvent(id);
        if (isCompleted(event)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Attendance is locked for this event");
        }
        attendanceService.removeAttendee(id, snowflake, name);
        populateAttendance(event);
        updateEvent(event);
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
