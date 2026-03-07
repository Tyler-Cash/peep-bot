package dev.tylercash.event.event.statemachine.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.statemachine.ExtendedState;
import org.springframework.statemachine.StateContext;

class DeleteOperationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-06-15T12:00:00Z"), ZoneId.of("UTC"));

    private DiscordService discordService;
    private EventRepository eventRepository;
    private DeleteOperation operation;

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        operation = new DeleteOperation(CLOCK, discordService, eventRepository);
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
    @DisplayName("guard: true when 3+ months after event")
    void guard_afterThreeMonths() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusMonths(4));
        assertTrue(operation.guard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("guard: false when less than 3 months after event")
    void guard_tooSoon() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusMonths(1));
        assertFalse(operation.guard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("action deletes channel and sets state to DELETED")
    void action() {
        Event event = new Event();
        event.setName("Test");
        event.setDateTime(ZonedDateTime.now(CLOCK).minusMonths(4));

        operation.action().execute(contextWithEvent(event));

        verify(discordService).deleteEventRoles(event);
        verify(discordService).deleteEventChannel(event);
        assertEquals(EventState.DELETED, event.getState());
        verify(eventRepository).save(event);
    }
}
