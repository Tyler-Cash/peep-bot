package dev.tylercash.event.event.statemachine.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.EventService;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.statemachine.ExtendedState;
import org.springframework.statemachine.StateContext;

class PreEventNotifyOperationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-06-15T12:00:00Z"), ZoneId.of("UTC"));

    private DiscordService discordService;
    private EventRepository eventRepository;
    private EventService eventService;
    private PreEventNotifyOperation operation;

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        eventService = mock(EventService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EventService> eventServiceProvider = mock(ObjectProvider.class);
        when(eventServiceProvider.getObject()).thenReturn(eventService);
        operation = new PreEventNotifyOperation(CLOCK, discordService, eventRepository, eventServiceProvider);
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
    @DisplayName("guard: true when within 2h before event")
    void guard_withinWindow() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).plusHours(1));
        assertTrue(operation.guard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("guard: false when event already started")
    void guard_afterEvent() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).minusMinutes(30));
        assertFalse(operation.guard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("guard: false when more than 2h before event")
    void guard_tooEarly() {
        Event event = new Event();
        event.setDateTime(ZonedDateTime.now(CLOCK).plusHours(3));
        assertFalse(operation.guard().evaluate(contextWithEvent(event)));
    }

    @Test
    @DisplayName("action populates attendance, sends notification, and sets state to NOTIFIED")
    void action() {
        Event event = new Event();
        event.setName("Test");
        event.setDateTime(ZonedDateTime.now(CLOCK).plusHours(1));

        operation.action().execute(contextWithEvent(event));

        verify(eventService).populateAttendance(event);
        verify(discordService).sendMessageBeforeEvent(event);
        assertEquals(EventState.NOTIFIED, event.getState());
        verify(eventRepository).save(event);
    }
}
