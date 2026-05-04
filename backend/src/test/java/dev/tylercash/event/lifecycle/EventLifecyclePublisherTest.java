package dev.tylercash.event.lifecycle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

class EventLifecyclePublisherTest {

    @Test
    void publish_insertsPendingRowPerMatchingListener() {
        ListenerInvocationRepository repo = mock(ListenerInvocationRepository.class);
        ApplicationEventPublisher spring = mock(ApplicationEventPublisher.class);
        DurableEventListener<EventLifecycleEvent.EventCreated> matching =
                stubListener("Test Listener", EventLifecycleEvent.EventCreated.class);
        DurableEventListener<EventLifecycleEvent.EventArchived> nonMatching =
                stubListener("Other Listener", EventLifecycleEvent.EventArchived.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<List<DurableEventListener<?>>> listenersProvider = mock(ObjectProvider.class);
        when(listenersProvider.getObject()).thenReturn(List.of(matching, nonMatching));
        var publisher = new EventLifecyclePublisher(spring, repo, listenersProvider, new SimpleMeterRegistry());

        UUID id = UUID.randomUUID();
        publisher.publish(new EventLifecycleEvent.EventCreated(id));

        verify(repo)
                .save(argThat(inv -> inv.getEventId().equals(id)
                        && inv.getLifecycleEventType().equals("EventCreated")
                        && inv.getListenerName().equals("Test Listener")
                        && inv.getStatus() == ListenerInvocationStatus.PENDING));
        verifyNoMoreInteractions(repo);
        verify(spring).publishEvent(any(EventLifecycleEvent.EventCreated.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <E extends EventLifecycleEvent> DurableEventListener<E> stubListener(
            String name, Class<E> eventType) {
        DurableEventListener mock = mock(DurableEventListener.class);
        when(mock.name()).thenReturn(name);
        when(mock.eventType()).thenReturn(eventType);
        return mock;
    }
}
