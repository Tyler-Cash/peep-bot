package dev.tylercash.event.lifecycle.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventDeleteListenerTest {

    private DiscordService discordService;
    private EventRepository eventRepository;
    private EventLifecyclePublisher publisher;
    private EventDeleteListener listener;

    private final UUID eventId = UUID.randomUUID();
    private Event event;

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        publisher = mock(EventLifecyclePublisher.class);
        listener = new EventDeleteListener(discordService, eventRepository, publisher);

        event = new Event();
        event.setId(eventId);
        event.setChannelId(123456789L);
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
    }

    @Test
    void happyPath_deletesDiscordResourcesAndPublishes() throws Exception {
        listener.handle(new EventLifecycleEvent.EventDeleteRequested(eventId));

        verify(discordService).deleteEventRoles(event);
        verify(discordService).deleteEventChannel(event);
        assertThat(event.getState()).isEqualTo(EventState.DELETED);
        verify(eventRepository).save(event);
        verify(publisher).publish(new EventLifecycleEvent.EventDeleted(eventId));
    }
}
