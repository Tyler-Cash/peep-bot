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

class ArchiveOperationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-06-15T12:00:00Z"), ZoneId.of("UTC"));

    private DiscordService discordService;
    private EventRepository eventRepository;
    private ArchiveOperation operation;

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        operation = new ArchiveOperation(CLOCK, discordService, eventRepository);
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
    @DisplayName("guard: true when past day+1 at 22:00")
    void guard_pastExpiry() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusDays(2));
        assertTrue(operation.guard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("guard: false when before day+1 at 22:00")
    void guard_beforeExpiry() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK));
        assertFalse(operation.guard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("action archives channel and sets state to ARCHIVED")
    void action() {
        Event event = new Event();
        event.setName("Test");
        event.setDateTime(ZonedDateTime.now(CLOCK).minusDays(2));

        operation.action().execute(contextWithEvent(event));

        verify(discordService).archiveEventChannel(event);
        assertEquals(EventState.ARCHIVED, event.getState());
        verify(eventRepository).save(event);
    }
}
