package dev.tylercash.event.event.statemachine.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import dev.tylercash.event.immich.ImmichConfiguration;
import dev.tylercash.event.immich.ImmichService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.statemachine.ExtendedState;
import org.springframework.statemachine.StateContext;

class PrepareAlbumOperationTest {

    private ImmichConfiguration immichConfiguration;
    private ImmichService immichService;
    private EventRepository eventRepository;
    private PrepareAlbumOperation operation;

    @BeforeEach
    void setUp() {
        immichConfiguration = new ImmichConfiguration();
        immichService = mock(ImmichService.class);
        eventRepository = mock(EventRepository.class);
        operation = new PrepareAlbumOperation(immichConfiguration, immichService, eventRepository);
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
    @DisplayName("guard: true when Immich enabled")
    void guard_enabled() {
        immichConfiguration.setEnabled(true);
        assertTrue(operation.guard().evaluate(contextWithEvent(new Event())));
    }

    @Test
    @DisplayName("guard: false when Immich disabled")
    void guard_disabled() {
        immichConfiguration.setEnabled(false);
        assertFalse(operation.guard().evaluate(contextWithEvent(new Event())));
    }

    @Test
    @DisplayName("action creates album and shared link, sets ALBUM_READY")
    void action_bothSucceed() {
        Event event = new Event();
        event.setName("Test");
        event.setDescription("Desc");

        when(immichService.createAlbum(anyString(), anyString())).thenReturn(Optional.of("album-id"));
        when(immichService.createSharedLink("album-id")).thenReturn(Optional.of("share-key"));

        operation.action().execute(contextWithEvent(event));

        assertEquals("album-id", event.getImmichAlbumId());
        assertEquals("share-key", event.getImmichShareKey());
        assertEquals(EventState.POST_ALBUM_READY, event.getState());
        verify(eventRepository).save(event);
    }

    @Test
    @DisplayName("action sets POST_ALBUM_READY when album and share link already present")
    void action_alreadyPresent() {
        Event event = new Event();
        event.setName("Test");
        event.setImmichAlbumId("album-id");
        event.setImmichShareKey("share-key");

        operation.action().execute(contextWithEvent(event));

        assertEquals(EventState.POST_ALBUM_READY, event.getState());
        verify(eventRepository).save(event);
        verifyNoInteractions(immichService);
    }

    @Test
    @DisplayName("action saves partial progress when album created but share link fails")
    void action_partialProgress() {
        Event event = new Event();
        event.setName("Test");
        event.setDescription("Desc");
        event.setState(EventState.PRE_NOTIFIED);

        when(immichService.createAlbum(anyString(), anyString())).thenReturn(Optional.of("album-id"));
        when(immichService.createSharedLink("album-id")).thenReturn(Optional.empty());

        operation.action().execute(contextWithEvent(event));

        assertEquals("album-id", event.getImmichAlbumId());
        assertNull(event.getImmichShareKey());
        assertEquals(EventState.PRE_NOTIFIED, event.getState());
        verify(eventRepository).save(event);
    }

    @Test
    @DisplayName("action does not save when album creation fails entirely")
    void action_totalFailure() {
        Event event = new Event();
        event.setName("Test");
        event.setDescription("Desc");
        event.setState(EventState.PRE_NOTIFIED);

        when(immichService.createAlbum(anyString(), anyString())).thenReturn(Optional.empty());

        operation.action().execute(contextWithEvent(event));

        assertNull(event.getImmichAlbumId());
        assertEquals(EventState.PRE_NOTIFIED, event.getState());
        verify(eventRepository, never()).save(event);
    }
}
