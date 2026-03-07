package dev.tylercash.event.event.statemachine.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import dev.tylercash.event.immich.ImmichService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.statemachine.ExtendedState;
import org.springframework.statemachine.StateContext;

class PostAlbumOperationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-06-15T12:00:00Z"), ZoneId.of("UTC"));

    private ImmichService immichService;
    private DiscordService discordService;
    private EventRepository eventRepository;
    private EventService eventService;
    private PostAlbumOperation operation;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        immichService = mock(ImmichService.class);
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        eventService = mock(EventService.class);
        ObjectProvider<EventService> eventServiceProvider = mock(ObjectProvider.class);
        when(eventServiceProvider.getObject()).thenReturn(eventService);
        operation = new PostAlbumOperation(CLOCK, immichService, discordService, eventRepository, eventServiceProvider);
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
    @DisplayName("guard: true when 1h+ after event")
    void guard_afterOneHour() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(2));
        assertTrue(operation.guard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("guard: false when less than 1h after event")
    void guard_tooSoon() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK));
        assertFalse(operation.guard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("action posts album link and sets state to ALBUM_POSTED")
    void action() {
        Event event = new Event();
        event.setName("Test");
        event.setImmichShareKey("share-key");

        when(immichService.getShareUrl("share-key")).thenReturn("https://immich.example.com/share/share-key");

        operation.action().execute(contextWithEvent(event));

        verify(discordService).sendAlbumLink(eq(event), eq("https://immich.example.com/share/share-key"));
        verify(eventService).populateAttendance(event);
        verify(discordService).updateEventMessage(event);
        assertEquals(EventState.POST_ALBUM_SHARED, event.getState());
        verify(eventRepository).save(event);
    }
}
