package dev.tylercash.event.lifecycle.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class EventCancelListenerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-05-04T18:00:00Z");

    private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private DiscordService discordService;
    private EventRepository eventRepository;
    private EventService eventService;
    private ObjectProvider<EventService> eventServiceProvider;
    private EventLifecyclePublisher publisher;
    private EventCancelListener listener;

    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        eventService = mock(EventService.class);
        eventServiceProvider = mock(ObjectProvider.class);
        publisher = mock(EventLifecyclePublisher.class);

        when(eventServiceProvider.getObject()).thenReturn(eventService);

        listener = new EventCancelListener(clock, discordService, eventRepository, eventServiceProvider, publisher);
    }

    @Test
    void handle_happyPath_prefixesNameCallsDiscordOpsTransitionsAndPublishes() throws Exception {
        Event event = new Event();
        event.setId(eventId);
        event.setName("Pizza Night");
        event.setMessageId(42L);
        event.setChannelId(99L);
        event.setNotifications(new HashSet<>());

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        listener.handle(new EventLifecycleEvent.EventCancelRequested(eventId));

        assertThat(event.getName()).isEqualTo("[CANCELLED] Pizza Night");

        verify(discordService).removeEventButtons(event);
        verify(eventService).populateAttendance(event);
        verify(discordService).updateEventMessage(event);

        verify(discordService).updateChannelName(event);
        verify(discordService).deletePrivateEventChannel(event);
        assertThat(event.getPrivateChannelId()).isNull();
        verify(discordService).archiveEventChannel(event);

        assertThat(event.getNotifications()).hasSize(1);
        Notification notification = event.getNotifications().iterator().next();
        assertThat(notification.getType()).isEqualTo(NotificationType.ATTENDANCE_LOCKED);
        assertThat(notification.getInstant()).isEqualTo(FIXED_INSTANT);
        assertThat(notification.getMessageId()).isEqualTo(0L);

        assertThat(event.getState()).isEqualTo(EventState.CANCELLED);
        verify(eventRepository).save(event);
        verify(publisher).publish(new EventLifecycleEvent.EventCancelled(eventId));
    }

    @Test
    void handle_alreadyCancelledName_doesNotDoublePrefixName() throws Exception {
        Event event = new Event();
        event.setId(eventId);
        event.setName("[CANCELLED] Pizza Night");
        event.setMessageId(0L);
        event.setChannelId(0L);
        event.setNotifications(new HashSet<>());

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        listener.handle(new EventLifecycleEvent.EventCancelRequested(eventId));

        assertThat(event.getName()).isEqualTo("[CANCELLED] Pizza Night");
    }

    @Test
    void handle_preChannelEvent_skipsDiscordOpsButStillTransitionsAndPublishes() throws Exception {
        Event event = new Event();
        event.setId(eventId);
        event.setName("Pizza Night");
        event.setMessageId(0L);
        event.setChannelId(0L);
        event.setNotifications(new HashSet<>());

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        listener.handle(new EventLifecycleEvent.EventCancelRequested(eventId));

        verifyNoInteractions(discordService);
        verifyNoInteractions(eventService);

        assertThat(event.getState()).isEqualTo(EventState.CANCELLED);
        verify(eventRepository).save(event);
        verify(publisher).publish(new EventLifecycleEvent.EventCancelled(eventId));
    }
}
