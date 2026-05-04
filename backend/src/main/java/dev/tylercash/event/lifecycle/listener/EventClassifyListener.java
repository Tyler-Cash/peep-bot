package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.lifecycle.DurableEventListener;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import dev.tylercash.event.rewind.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventClassifyListener implements DurableEventListener<EventLifecycleEvent.EventRolesReady> {

    private final EmbeddingService embeddingService;
    private final EventRepository eventRepository;
    private final EventLifecyclePublisher publisher;

    @Override
    public String name() {
        return "Event Classify";
    }

    @Override
    public Class<EventLifecycleEvent.EventRolesReady> eventType() {
        return EventLifecycleEvent.EventRolesReady.class;
    }

    @Override
    @Transactional
    public void handle(EventLifecycleEvent.EventRolesReady e) throws Exception {
        Event event = eventRepository.findById(e.eventId()).orElseThrow();
        log.info("Classifying event: {}", event.getName());

        // classifyEvent is idempotent (find-or-create embedding), so no listener guard needed.
        try {
            embeddingService.classifyEvent(event);
        } catch (Exception ex) {
            log.warn("Classification failed for event '{}', continuing", event.getName(), ex);
        }

        event.setState(EventState.CLASSIFY);
        eventRepository.save(event);
        publisher.publish(new EventLifecycleEvent.EventClassified(event.getId()));
    }
}
