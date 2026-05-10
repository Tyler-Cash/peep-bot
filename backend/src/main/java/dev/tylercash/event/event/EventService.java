package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.EventCategoryRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.event.model.*;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import dev.tylercash.event.rewind.EmbeddingService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {
    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final Clock clock;
    private final AttendanceService attendanceService;
    private final DiscordUserCacheService discordUserCacheService;
    private final EmbeddingService embeddingService;
    private final EventCategoryRepository eventCategoryRepository;
    private final CoverImageService coverImageService;
    private final EventLifecyclePublisher lifecyclePublisher;

    @Observed(name = "event.create")
    @CacheEvict(value = "activeEvents", allEntries = true)
    @Transactional
    public String createEvent(Event event) {
        log.info("Creating event '{}' by creator {}", event.getName(), event.getCreator());
        coverImageService.refreshIfNeeded(event);
        eventRepository.save(event);
        MDC.put("eventId", event.getId().toString());
        lifecyclePublisher.publish(new EventLifecycleEvent.EventCreated(event.getId()));
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
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 500))
    @Transactional
    public Event updateEvent(Event event) {
        MDC.put("eventId", event.getId().toString());
        log.info("Updating event '{}' id={}", event.getName(), event.getId());
        coverImageService.refreshIfNeeded(event);
        discordService.updateEventMessage(event);
        discordService.updateChannelName(event);
        eventRepository.save(event);
        return event;
    }

    @Cacheable(value = "activeEvents", key = "#guildId + '-' + #pageable.pageNumber + '-' + #pageable.pageSize")
    public Page<Event> getActiveEvents(Pageable pageable, long guildId) {
        Pageable sorted = pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(
                        pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.ASC, "dateTime"));
        return eventRepository.findAllByStateNotInAndServerId(
                sorted,
                List.of(EventState.CREATED, EventState.CANCELLED, EventState.ARCHIVED, EventState.DELETED),
                guildId);
    }

    public String getEventCategory(UUID eventId) {
        return eventCategoryRepository
                .findById(eventId)
                .map(EventCategory::getCategoryLabel)
                .filter(label -> !label.isBlank())
                .orElse("unknown");
    }

    public Map<UUID, String> getEventCategories(Collection<UUID> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, String> categories = new HashMap<>();
        for (UUID eventId : eventIds) {
            categories.put(eventId, "unknown");
        }
        eventCategoryRepository.findByEventIdIn(eventIds).forEach(ec -> {
            String label = ec.getCategoryLabel();
            categories.put(ec.getEventId(), (label == null || label.isBlank()) ? "unknown" : label);
        });
        return categories;
    }

    public boolean isCompleted(Event event) {
        return event.getState().ordinal() >= EventState.POST_COMPLETED.ordinal()
                || ZonedDateTime.now(clock).isAfter(event.getDateTime().plusHours(6));
    }

    public boolean isArchived(Event event) {
        return event.getState().ordinal() >= EventState.ARCHIVED.ordinal();
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
        if (isArchived(event)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Event is archived");
        }
        lifecyclePublisher.publish(new EventLifecycleEvent.EventCancelRequested(event.getId()));
    }

    @Caching(
            evict = {
                @CacheEvict(value = "activeEvents", allEntries = true),
                @CacheEvict(value = "eventDetail", key = "#id")
            })
    @Observed(name = "event.remove-attendee")
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 500))
    @Transactional
    public void removeAttendee(UUID id, String snowflake, String name) {
        MDC.put("eventId", id.toString());
        log.info("Removing attendee from event id={} snowflake={} name={}", id, snowflake, name);
        Event event = getEvent(id);
        if (isArchived(event)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Event is archived");
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
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 500))
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

        long guildId = event.getServerId();
        Map<String, String> nameMap = discordUserCacheService.getDisplayNames(guildId, allSnowflakes);

        event.setAccepted(toAttendeeSet(summary.accepted(), nameMap, guildId));
        event.setDeclined(toAttendeeSet(summary.declined(), nameMap, guildId));
        event.setMaybe(toAttendeeSet(summary.maybe(), nameMap, guildId));

        String creatorName = nameMap.get(event.getCreator());
        event.setCreatorDisplayName(creatorName != null ? creatorName : event.getCreator());
    }

    @CacheEvict(value = "eventDetail", key = "#id")
    @Observed(name = "event.recategorize")
    public void recategorizeEvent(UUID id) {
        MDC.put("eventId", id.toString());
        log.info("Recategorizing event id={}", id);
        Event event = getEvent(id);
        embeddingService.classifyEvent(event);
    }

    private Set<Attendee> toAttendeeSet(List<AttendanceRecord> records, Map<String, String> nameMap, long guildId) {
        Set<Attendee> attendees = new LinkedHashSet<>();
        for (AttendanceRecord record : records) {
            String displayName;
            if (record.getSnowflake() != null) {
                displayName = nameMap.getOrDefault(
                        record.getSnowflake(), discordUserCacheService.getDisplayName(guildId, record.getSnowflake()));
            } else {
                displayName = record.getName();
            }
            attendees.add(Attendee.createWithTimestamp(record.getSnowflake(), displayName, record.getRecordedAt()));
        }
        return attendees;
    }
}
