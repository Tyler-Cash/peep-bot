package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.lifecycle.DurableEventListener;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.tfnsw.TfnswOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Lifecycle listener that fires once when an event is created. Delegates to
 * {@link TfnswOrchestrator} which checks the per-guild TfNSW flag and posts a
 * disruption notice if anything noteworthy applies to the event date.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TfnswEventCreatedListener implements DurableEventListener<EventLifecycleEvent.EventCreated> {

    private final TfnswOrchestrator orchestrator;

    @Override
    public String name() {
        return "TfNSW Event Created";
    }

    @Override
    public Class<EventLifecycleEvent.EventCreated> eventType() {
        return EventLifecycleEvent.EventCreated.class;
    }

    @Override
    public void handle(EventLifecycleEvent.EventCreated e) throws Exception {
        orchestrator.process(e.eventId(), false);
    }
}
