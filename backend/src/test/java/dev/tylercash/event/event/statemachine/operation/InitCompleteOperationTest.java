package dev.tylercash.event.event.statemachine.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import dev.tylercash.event.immich.ImmichService;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.statemachine.ExtendedState;
import org.springframework.statemachine.StateContext;

class InitCompleteOperationTest {

    private ImmichService immichService;
    private DiscordService discordService;
    private EventRepository eventRepository;
    private InitCompleteOperation operation;

    @BeforeEach
    void setUp() {
        immichService = mock(ImmichService.class);
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        operation = new InitCompleteOperation(immichService, discordService, eventRepository);
    }

    @SuppressWarnings("unchecked")
    private StateContext<EventState, EventStateMachineEvent> contextWithEvent(Event event) {
        StateContext<EventState, EventStateMachineEvent> ctx = mock(StateContext.class);
        ExtendedState extState = mock(ExtendedState.class);
        when(ctx.getExtendedState()).thenReturn(extState);
        when(extState.get("event", Event.class)).thenReturn(event);
        return ctx;
    }

    @Test
    @DisplayName("action creates album, re-renders message, and sets state to PLANNED")
    void action_withAlbum() {
        Event event = new Event();
        event.setName("Test");
        event.setDescription("Desc");
        event.setDateTime(ZonedDateTime.now());

        when(immichService.createAlbum(anyString(), anyString())).thenReturn(Optional.of("album-id"));
        when(immichService.createSharedLink("album-id")).thenReturn(Optional.of("share-key"));

        operation.action().execute(contextWithEvent(event));

        assertEquals("album-id", event.getImmichAlbumId());
        assertEquals("share-key", event.getImmichShareKey());
        verify(discordService).updateEventMessage(event);
        assertEquals(EventState.PLANNED, event.getState());
        verify(eventRepository).save(event);
    }

    @Test
    @DisplayName("action transitions to PLANNED even if album creation fails")
    void action_withoutImmich() {
        Event event = new Event();
        event.setName("Test");
        event.setDescription("Desc");
        event.setDateTime(ZonedDateTime.now());

        when(immichService.createAlbum(anyString(), anyString())).thenReturn(Optional.empty());

        operation.action().execute(contextWithEvent(event));

        verify(discordService).updateEventMessage(event);
        assertEquals(EventState.PLANNED, event.getState());
        verify(eventRepository).save(event);
    }

    @Test
    @DisplayName("action re-renders message on PLANNED entry")
    void action_reRendersMessage() {
        Event event = new Event();
        event.setName("Test");
        event.setDateTime(ZonedDateTime.now());

        when(immichService.createAlbum(anyString(), anyString())).thenReturn(Optional.empty());

        operation.action().execute(contextWithEvent(event));

        verify(discordService).updateEventMessage(event);
    }
}
