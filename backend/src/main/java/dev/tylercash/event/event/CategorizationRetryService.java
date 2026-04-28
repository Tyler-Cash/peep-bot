package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.EventClassificationAttemptRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.rewind.EmbeddingService;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CategorizationRetryService {

    static final Duration MAX_RETRY_WINDOW = Duration.ofHours(3);
    static final int[] BACKOFF_MINUTES = {5, 15, 45, 135};

    private final EventRepository eventRepository;
    private final EventClassificationAttemptRepository attemptRepository;
    private final EmbeddingService embeddingService;
    private final Clock clock;

    @Scheduled(fixedRate = 60000)
    @SchedulerLock(name = "categorizationRetry")
    public void retryUnclassifiedEvents() {
        Page<Event> events = eventRepository.findPlannedEventsWithoutCategory(Pageable.ofSize(100));
        if (events.isEmpty()) return;

        log.info("Categorization retry: {} PLANNED events without category", events.getTotalElements());
        ZonedDateTime now = ZonedDateTime.now(clock);

        for (Event event : events) {
            try {
                processEvent(event, now);
            } catch (Exception e) {
                log.error("Error in categorization retry for '{}'", event.getName(), e);
            }
        }
    }

    private void processEvent(Event event, ZonedDateTime now) {
        Optional<EventClassificationAttempt> attemptOpt = attemptRepository.findById(event.getId());

        if (attemptOpt.isEmpty()) {
            log.info("First categorization retry attempt for '{}'", event.getName());
            tryClassify(event);
            createAttemptRecord(event, now, 0);
            return;
        }

        EventClassificationAttempt attempt = attemptOpt.get();

        if (attempt.getFirstAttemptAt().plus(MAX_RETRY_WINDOW).isBefore(now)) {
            log.warn(
                    "Categorization abandoned for '{}' — 3h window expired after {} attempts",
                    event.getName(),
                    attempt.getAttemptCount());
            return;
        }

        if (attempt.getNextRetryAt().isAfter(now)) {
            return;
        }

        log.info("Retrying categorization for '{}' (attempt {})", event.getName(), attempt.getAttemptCount() + 1);
        tryClassify(event);
        updateAttemptRecord(attempt, now);
    }

    private void tryClassify(Event event) {
        try {
            embeddingService.classifyEvent(event);
        } catch (Exception e) {
            log.warn("Categorization attempt failed for '{}'", event.getName(), e);
        }
    }

    private void createAttemptRecord(Event event, ZonedDateTime now, int completedAttempts) {
        EventClassificationAttempt attempt = new EventClassificationAttempt();
        attempt.setEventId(event.getId());
        attempt.setAttemptCount(1);
        attempt.setFirstAttemptAt(now);
        attempt.setNextRetryAt(now.plusMinutes(backoffMinutes(completedAttempts)));
        attemptRepository.save(attempt);
    }

    private void updateAttemptRecord(EventClassificationAttempt attempt, ZonedDateTime now) {
        int newCount = attempt.getAttemptCount() + 1;
        attempt.setAttemptCount(newCount);
        attempt.setNextRetryAt(now.plusMinutes(backoffMinutes(newCount - 1)));
        attemptRepository.save(attempt);
    }

    private int backoffMinutes(int attemptIndex) {
        return BACKOFF_MINUTES[Math.min(attemptIndex, BACKOFF_MINUTES.length - 1)];
    }
}
