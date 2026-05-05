package dev.tylercash.event.lifecycle.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class PreEventNotificationListenerTest {

    private DiscordService discordService;
    private EventRepository eventRepository;
    private EventService eventService;
    private EventLifecyclePublisher publisher;
    private PreEventNotificationListener listener;

    private final UUID eventId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        eventService = mock(EventService.class);
        publisher = mock(EventLifecyclePublisher.class);

        ObjectProvider<EventService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(eventService);

        listener = new PreEventNotificationListener(discordService, eventRepository, provider, publisher);
    }

    @Test
    void handle_sendsNotificationTransitionsStateAndPublishes() throws Exception {
        Event event = new Event();
        event.setId(eventId);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        listener.handle(new EventLifecycleEvent.EventPreNotifyDue(eventId));

        verify(eventService).populateAttendance(event);
        verify(discordService).sendMessageBeforeEvent(event);
        assertThat(event.getState()).isEqualTo(EventState.PRE_NOTIFIED);
        verify(eventRepository).save(event);
        verify(publisher).publish(new EventLifecycleEvent.EventPreNotified(eventId));
    }
}
