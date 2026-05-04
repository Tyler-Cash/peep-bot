package dev.tylercash.event.lifecycle;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class EventLifecyclePublisher {
    private final ApplicationEventPublisher springPublisher;
    private final ListenerInvocationRepository invocations;
    private final List<DurableEventListener<?>> listeners;
    private final MeterRegistry meterRegistry;

    public EventLifecyclePublisher(
            ApplicationEventPublisher springPublisher,
            ListenerInvocationRepository invocations,
            List<DurableEventListener<?>> listeners,
            MeterRegistry meterRegistry) {
        this.springPublisher = springPublisher;
        this.invocations = invocations;
        this.listeners = listeners;
        this.meterRegistry = meterRegistry;
    }

    public void publish(EventLifecycleEvent event) {
        String type = event.getClass().getSimpleName();
        log.debug("Publishing lifecycle event {} for event {}", type, event.eventId());
        meterRegistry.counter("event.lifecycle.published", "type", type).increment();

        for (DurableEventListener<?> listener : listeners) {
            if (!listener.eventType().isInstance(event)) continue;
            ListenerInvocation row = new ListenerInvocation();
            row.setEventId(event.eventId());
            row.setLifecycleEventType(type);
            row.setListenerName(listener.name());
            row.setStatus(ListenerInvocationStatus.PENDING);
            invocations.save(row);
        }
        springPublisher.publishEvent(event);
    }
}
