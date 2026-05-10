package dev.tylercash.event.lifecycle.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import dev.tylercash.event.rewind.EmbeddingService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventClassifyListenerTest {

    private EmbeddingService embeddingService;
    private EventRepository eventRepository;
    private EventLifecyclePublisher publisher;
    private EventClassifyListener listener;

    private final UUID eventId = UUID.randomUUID();
    private Event event;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        eventRepository = mock(EventRepository.class);
        publisher = mock(EventLifecyclePublisher.class);
        listener = new EventClassifyListener(embeddingService, eventRepository, publisher);

        event = new Event();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
    }

    @Test
    void happyPath_classifiesSetsSaveAndPublishes() throws Exception {
        listener.handle(new EventLifecycleEvent.EventRolesReady(eventId));

        verify(embeddingService).classifyEvent(event);
        // Listener writes state via a narrow column update — never a full-row
        // save() — so a slow embedding call can't clobber concurrent edits.
        verify(eventRepository).updateState(event.getId(), EventState.CLASSIFY);
        verify(eventRepository, never()).save(any(Event.class));
        verify(publisher).publish(new EventLifecycleEvent.EventClassified(event.getId()));
        assertThat(event.getState()).isNotEqualTo(EventState.CLASSIFY);
    }

    @Test
    void classificationException_isSwallowed_stateUpdatedAndPublishStillCalled() throws Exception {
        doThrow(new RuntimeException("embedding service unavailable"))
                .when(embeddingService)
                .classifyEvent(event);

        listener.handle(new EventLifecycleEvent.EventRolesReady(eventId));

        verify(eventRepository).updateState(event.getId(), EventState.CLASSIFY);
        verify(eventRepository, never()).save(any(Event.class));
        verify(publisher).publish(new EventLifecycleEvent.EventClassified(event.getId()));
    }
}
