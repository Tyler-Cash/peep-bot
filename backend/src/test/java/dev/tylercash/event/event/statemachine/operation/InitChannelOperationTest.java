package dev.tylercash.event.event.statemachine.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import java.time.ZonedDateTime;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.statemachine.ExtendedState;
import org.springframework.statemachine.StateContext;

class InitChannelOperationTest {

    private DiscordService discordService;
    private EventRepository eventRepository;
    private InitChannelOperation operation;

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        operation = new InitChannelOperation(discordService, eventRepository);
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
    @DisplayName("action creates channel, posts message, and sets state to INIT_CHANNEL")
    void action_success() {
        Event event = new Event();
        event.setName("Test");
        event.setDateTime(ZonedDateTime.now());

        TextChannel channel = mock(TextChannel.class);
        when(channel.getIdLong()).thenReturn(123L);
        when(discordService.createEventChannel(event)).thenReturn(channel);

        Message message = mock(Message.class);
        when(message.getGuildIdLong()).thenReturn(456L);
        when(message.getIdLong()).thenReturn(789L);
        when(discordService.getChannel(event)).thenReturn(channel);
        when(discordService.postEventMessage(event, channel)).thenReturn(message);

        operation.action().execute(contextWithEvent(event));

        assertEquals(123L, event.getChannelId());
        assertEquals(456L, event.getServerId());
        assertEquals(789L, event.getMessageId());
        assertEquals(EventState.INIT_CHANNEL, event.getState());
        verify(discordService).sortActiveChannels();
        verify(eventRepository, times(2)).save(event);
    }

    @Test
    @DisplayName("action skips channel creation if channelId already set")
    void action_partialProgress() {
        Event event = new Event();
        event.setName("Test");
        event.setDateTime(ZonedDateTime.now());
        event.setChannelId(123L);

        TextChannel channel = mock(TextChannel.class);
        when(discordService.getChannel(event)).thenReturn(channel);

        Message message = mock(Message.class);
        when(message.getGuildIdLong()).thenReturn(456L);
        when(message.getIdLong()).thenReturn(789L);
        when(discordService.postEventMessage(event, channel)).thenReturn(message);

        operation.action().execute(contextWithEvent(event));

        verify(discordService, never()).createEventChannel(event);
        assertEquals(EventState.INIT_CHANNEL, event.getState());
    }
}
