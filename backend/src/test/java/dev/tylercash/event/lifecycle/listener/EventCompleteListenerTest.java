package dev.tylercash.event.lifecycle.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
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

class EventCompleteListenerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-05-04T18:00:00Z");

    private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private DiscordService discordService;
    private EventRepository eventRepository;
    private EventLifecyclePublisher publisher;
    private EventCompleteListener listener;

    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        publisher = mock(EventLifecyclePublisher.class);

        listener = new EventCompleteListener(clock, discordService, eventRepository, publisher);
    }

    @Test
    void handle_removesButtonsLocksAttendanceTransitionsStateAndPublishes() throws Exception {
        Event event = new Event();
        event.setId(eventId);
        event.setNotifications(new HashSet<>());

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        listener.handle(new EventLifecycleEvent.EventCompletionDue(eventId));

        verify(discordService).removeEventButtons(event);

        assertThat(event.getNotifications()).hasSize(1);
        Notification notification = event.getNotifications().iterator().next();
        assertThat(notification.getType()).isEqualTo(NotificationType.ATTENDANCE_LOCKED);
        assertThat(notification.getInstant()).isEqualTo(FIXED_INSTANT);
        assertThat(notification.getMessageId()).isEqualTo(0L);

        assertThat(event.getState()).isEqualTo(EventState.POST_COMPLETED);
        verify(eventRepository).save(event);
        verify(publisher).publish(new EventLifecycleEvent.EventCompleted(eventId));
    }
}
