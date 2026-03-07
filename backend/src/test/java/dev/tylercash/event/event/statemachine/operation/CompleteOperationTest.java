package dev.tylercash.event.event.statemachine.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.model.NotificationType;
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

class CompleteOperationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-06-15T12:00:00Z"), ZoneId.of("UTC"));

    private DiscordService discordService;
    private EventRepository eventRepository;
    private CompleteOperation operation;

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        operation = new CompleteOperation(CLOCK, discordService, eventRepository);
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
    @DisplayName("guard: true when 6h+ after event")
    void guard_afterSixHours() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(7));
        assertTrue(operation.guard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("guard: false when less than 6h after event")
    void guard_tooSoon() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(3));
        assertFalse(operation.guard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("action removes buttons, adds notification, and sets state to COMPLETED")
    void action() {
        Event event = new Event();
        event.setName("Test");
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(7));

        operation.action().execute(contextWithEvent(event));

        verify(discordService).removeEventButtons(event);
        assertEquals(EventState.COMPLETED, event.getState());
        assertTrue(event.getNotifications().stream().anyMatch(n -> n.getType() == NotificationType.ATTENDANCE_LOCKED));
        verify(eventRepository).save(event);
    }
}
