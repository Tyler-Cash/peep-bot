package dev.tylercash.event.lifecycle.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class EventArchiveListenerTest {

    private DiscordService discordService;
    private EventRepository eventRepository;
    private EventService eventService;
    private EventLifecyclePublisher publisher;

    private final UUID eventId = UUID.randomUUID();
    private Event event;

    @SuppressWarnings("unchecked")
    private EventArchiveListener listenerWithClock(Clock clock) {
        ObjectProvider<EventService> eventServiceProvider = mock(ObjectProvider.class);
        when(eventServiceProvider.getObject()).thenReturn(eventService);
        return new EventArchiveListener(clock, discordService, eventRepository, eventServiceProvider, publisher);
    }

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        eventService = mock(EventService.class);
        publisher = mock(EventLifecyclePublisher.class);

        // event.dateTime = 2026-05-04T12:00Z
        event = new Event();
        event.setId(eventId);
        event.setName("Test Event");
        event.setDateTime(ZonedDateTime.of(2026, 5, 4, 12, 0, 0, 0, ZoneOffset.UTC));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
    }

    @Test
    void tooEarly_throwsIllegalStateException_noDiscordInteractions() {
        // clock = 2026-05-04T18:00Z; archiveTime = 2026-05-05T22:00Z — well in the future
        Clock clock = Clock.fixed(Instant.parse("2026-05-04T18:00:00Z"), ZoneOffset.UTC);
        EventArchiveListener listener = listenerWithClock(clock);

        assertThatThrownBy(() -> listener.handle(new EventLifecycleEvent.EventArchivalDue(eventId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not yet ready for archival");

        verifyNoInteractions(discordService);
        verify(eventRepository, never()).save(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void happyPath_archivesEventAndPublishes() throws Exception {
        // clock = 2026-05-06T22:01Z; archiveTime = 2026-05-05T22:00Z — already past
        Clock clock = Clock.fixed(Instant.parse("2026-05-06T22:01:00Z"), ZoneOffset.UTC);
        EventArchiveListener listener = listenerWithClock(clock);

        listener.handle(new EventLifecycleEvent.EventArchivalDue(eventId));

        verify(eventService).populateAttendance(event);
        verify(discordService).updateEventMessage(event);
        verify(discordService).deletePrivateEventChannel(event);
        assertThat(event.getPrivateChannelId()).isNull();
        verify(discordService).archiveEventChannel(event);
        assertThat(event.getState()).isEqualTo(EventState.ARCHIVED);
        verify(eventRepository).save(event);
        verify(publisher).publish(new EventLifecycleEvent.EventArchived(eventId));
    }
}
