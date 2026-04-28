package dev.tylercash.event.event.statemachine.operation;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import dev.tylercash.event.rewind.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClassifyOperation {

    private final EmbeddingService embeddingService;
    private final EventRepository eventRepository;

    public Action<EventState, EventStateMachineEvent> action() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            log.info("Classifying event: {}", event.getName());

            try {
                embeddingService.classifyEvent(event);
            } catch (Exception e) {
                log.warn("Classification failed for event '{}', continuing", event.getName(), e);
            }

            event.setState(EventState.CLASSIFY);
            eventRepository.save(event);
        };
    }
}
