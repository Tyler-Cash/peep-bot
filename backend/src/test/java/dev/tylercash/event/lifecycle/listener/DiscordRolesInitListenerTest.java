package dev.tylercash.event.lifecycle.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiscordRolesInitListenerTest {

    private DiscordService discordService;
    private EventRepository eventRepository;
    private EventLifecyclePublisher publisher;
    private DiscordRolesInitListener listener;

    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        publisher = mock(EventLifecyclePublisher.class);
        listener = new DiscordRolesInitListener(discordService, eventRepository, publisher);
    }

    @Test
    void handle_createsRolesAssignsCreatorAndPublishesNextEvent() throws Exception {
        Event event = new Event();
        event.setId(eventId);
        event.setCreator("123");

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        listener.handle(new EventLifecycleEvent.EventChannelReady(eventId));

        verify(discordService).createEventRoles(event);
        verify(discordService).assignEventRole(event, "123", AttendanceStatus.ACCEPTED);
        assertThat(event.getState()).isEqualTo(EventState.INIT_ROLES);
        verify(eventRepository).save(event);
        verify(publisher).publish(new EventLifecycleEvent.EventRolesReady(eventId));
    }

    @Test
    void handle_creatorRoleAssignmentThrows_exceptionSwallowedAndListenerCompletes() throws Exception {
        Event event = new Event();
        event.setId(eventId);
        event.setCreator("123");

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        doThrow(new RuntimeException("Discord unavailable"))
                .when(discordService)
                .assignEventRole(event, "123", AttendanceStatus.ACCEPTED);

        // Should not propagate
        listener.handle(new EventLifecycleEvent.EventChannelReady(eventId));

        verify(discordService).createEventRoles(event);
        assertThat(event.getState()).isEqualTo(EventState.INIT_ROLES);
        verify(eventRepository).save(event);
        verify(publisher).publish(new EventLifecycleEvent.EventRolesReady(eventId));
    }

    @Test
    void handle_creatorIsNull_skipsRoleAssignmentAndCompletes() throws Exception {
        Event event = new Event();
        event.setId(eventId);
        event.setCreator(null);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        listener.handle(new EventLifecycleEvent.EventChannelReady(eventId));

        verify(discordService).createEventRoles(event);
        verify(discordService, never()).assignEventRole(any(), any(), any());
        assertThat(event.getState()).isEqualTo(EventState.INIT_ROLES);
        verify(eventRepository).save(event);
        verify(publisher).publish(new EventLifecycleEvent.EventRolesReady(eventId));
    }
}
