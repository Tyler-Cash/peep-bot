package dev.tylercash.event.event.statemachine.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.statemachine.ExtendedState;
import org.springframework.statemachine.StateContext;

class InitRolesOperationTest {

    private DiscordService discordService;
    private EventRepository eventRepository;
    private InitRolesOperation operation;

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        operation = new InitRolesOperation(discordService, eventRepository);
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
    @DisplayName("action creates roles and sets state to INIT_ROLES")
    void action_success() {
        Event event = new Event();
        event.setName("Test");
        event.setDateTime(ZonedDateTime.now());

        operation.action().execute(contextWithEvent(event));

        verify(discordService).createEventRoles(event);
        assertEquals(EventState.INIT_ROLES, event.getState());
        verify(eventRepository).save(event);
    }
}
