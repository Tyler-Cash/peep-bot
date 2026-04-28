package dev.tylercash.event.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventClassificationAttemptRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.rewind.EmbeddingService;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

@ExtendWith(MockitoExtension.class)
class CategorizationRetryServiceTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2025, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    private static Event event(UUID id) {
        Event e = new Event(0L, 0L, 0L, "Test", "creator", ZonedDateTime.now(), "desc");
        e.setId(id);
        e.setState(EventState.PLANNED);
        return e;
    }

    private CategorizationRetryService service(
            EventRepository eventRepo,
            EventClassificationAttemptRepository attemptRepo,
            EmbeddingService embeddingService) {
        Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        return new CategorizationRetryService(eventRepo, attemptRepo, embeddingService, clock);
    }

    @Test
    void newUnclassifiedEvent_createsAttemptAndCallsClassify() {
        UUID id = UUID.randomUUID();
        Event event = event(id);
        EventRepository eventRepo = mock(EventRepository.class);
        EventClassificationAttemptRepository attemptRepo = mock(EventClassificationAttemptRepository.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        when(eventRepo.findPlannedEventsWithoutCategory(any())).thenReturn(new PageImpl<>(List.of(event)));
        when(attemptRepo.findById(id)).thenReturn(Optional.empty());

        service(eventRepo, attemptRepo, embeddingService).retryUnclassifiedEvents();

        verify(embeddingService).classifyEvent(event);
        verify(attemptRepo).save(any(EventClassificationAttempt.class));
    }

    @Test
    void eventDueForRetry_callsClassifyAndUpdatesAttempt() {
        UUID id = UUID.randomUUID();
        Event event = event(id);
        EventRepository eventRepo = mock(EventRepository.class);
        EventClassificationAttemptRepository attemptRepo = mock(EventClassificationAttemptRepository.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        EventClassificationAttempt attempt = new EventClassificationAttempt();
        attempt.setEventId(id);
        attempt.setAttemptCount(1);
        attempt.setFirstAttemptAt(NOW.minusMinutes(10));
        attempt.setNextRetryAt(NOW.minusMinutes(1));

        when(eventRepo.findPlannedEventsWithoutCategory(any())).thenReturn(new PageImpl<>(List.of(event)));
        when(attemptRepo.findById(id)).thenReturn(Optional.of(attempt));

        service(eventRepo, attemptRepo, embeddingService).retryUnclassifiedEvents();

        verify(embeddingService).classifyEvent(event);
        verify(attemptRepo).save(attempt);
    }

    @Test
    void eventNotYetDueForRetry_skipsClassify() {
        UUID id = UUID.randomUUID();
        Event event = event(id);
        EventRepository eventRepo = mock(EventRepository.class);
        EventClassificationAttemptRepository attemptRepo = mock(EventClassificationAttemptRepository.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        EventClassificationAttempt attempt = new EventClassificationAttempt();
        attempt.setEventId(id);
        attempt.setAttemptCount(1);
        attempt.setFirstAttemptAt(NOW.minusMinutes(2));
        attempt.setNextRetryAt(NOW.plusMinutes(3));

        when(eventRepo.findPlannedEventsWithoutCategory(any())).thenReturn(new PageImpl<>(List.of(event)));
        when(attemptRepo.findById(id)).thenReturn(Optional.of(attempt));

        service(eventRepo, attemptRepo, embeddingService).retryUnclassifiedEvents();

        verify(embeddingService, never()).classifyEvent(any());
    }

    @Test
    void eventPast3hWindow_skipsClassify() {
        UUID id = UUID.randomUUID();
        Event event = event(id);
        EventRepository eventRepo = mock(EventRepository.class);
        EventClassificationAttemptRepository attemptRepo = mock(EventClassificationAttemptRepository.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);

        EventClassificationAttempt attempt = new EventClassificationAttempt();
        attempt.setEventId(id);
        attempt.setAttemptCount(4);
        attempt.setFirstAttemptAt(NOW.minusHours(4));
        attempt.setNextRetryAt(NOW.minusMinutes(1));

        when(eventRepo.findPlannedEventsWithoutCategory(any())).thenReturn(new PageImpl<>(List.of(event)));
        when(attemptRepo.findById(id)).thenReturn(Optional.of(attempt));

        service(eventRepo, attemptRepo, embeddingService).retryUnclassifiedEvents();

        verify(embeddingService, never()).classifyEvent(any());
    }
}
