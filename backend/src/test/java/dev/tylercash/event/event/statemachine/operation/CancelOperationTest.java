package dev.tylercash.event.event.statemachine.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class CancelOperationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-06-15T12:00:00Z"), ZoneId.of("UTC"));

    private DiscordService discordService;
    private EventRepository eventRepository;
    private EventService eventService;
    private CancelOperation operation;

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        eventService = mock(EventService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EventService> eventServiceProvider = mock(ObjectProvider.class);
        when(eventServiceProvider.getObject()).thenReturn(eventService);
        operation = new CancelOperation(CLOCK, discordService, eventRepository, eventServiceProvider);
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
    @DisplayName("action populates attendance, updates message, and sets state to ARCHIVED")
    void action() {
        Event event = new Event();
        event.setName("Test");
        event.setMessageId(123L);
        event.setChannelId(456L);
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(1));

        operation.action().execute(contextWithEvent(event));

        assertEquals("[CANCELLED] Test", event.getName());
        verify(discordService).removeEventButtons(event);
        verify(eventService).populateAttendance(event);
        verify(discordService).updateEventMessage(event);
        verify(discordService).updateChannelName(event);
        verify(discordService).archiveEventChannel(event);
        assertEquals(EventState.ARCHIVED, event.getState());
        verify(eventRepository).save(event);
    }

    @Test
    @DisplayName("action skips Discord calls when event has no channel or message")
    void action_fromCreatedState() {
        Event event = new Event();
        event.setName("Test");
        event.setDateTime(ZonedDateTime.now(CLOCK).minusHours(1));

        operation.action().execute(contextWithEvent(event));

        assertEquals("[CANCELLED] Test", event.getName());
        verifyNoInteractions(discordService);
        assertEquals(EventState.ARCHIVED, event.getState());
        verify(eventRepository).save(event);
    }
}
