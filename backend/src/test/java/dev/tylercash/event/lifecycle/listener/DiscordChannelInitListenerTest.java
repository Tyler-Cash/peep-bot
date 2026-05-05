package dev.tylercash.event.lifecycle.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import java.util.Optional;
import java.util.UUID;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiscordChannelInitListenerTest {

    private DiscordService discordService;
    private EventRepository eventRepository;
    private EventLifecyclePublisher publisher;
    private DiscordChannelInitListener listener;

    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        publisher = mock(EventLifecyclePublisher.class);
        listener = new DiscordChannelInitListener(discordService, eventRepository, publisher);
    }

    @Test
    void fullInit_setsChannelMessageServerIdAndState() throws Exception {
        Event event = new Event();
        event.setId(eventId);
        event.setChannelId(0);
        event.setMessageId(0);

        TextChannel stubChannel = mock(TextChannel.class);
        when(stubChannel.getIdLong()).thenReturn(42L);

        Message stubMessage = mock(Message.class);
        when(stubMessage.getGuildIdLong()).thenReturn(99L);
        when(stubMessage.getIdLong()).thenReturn(88L);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(discordService.createEventChannel(event)).thenReturn(stubChannel);
        when(discordService.getChannel(event)).thenReturn(stubChannel);
        when(discordService.postEventMessage(eq(event), any(TextChannel.class))).thenReturn(stubMessage);

        listener.handle(new EventLifecycleEvent.EventCreated(eventId));

        assertThat(event.getChannelId()).isEqualTo(42L);
        assertThat(event.getMessageId()).isEqualTo(88L);
        assertThat(event.getServerId()).isEqualTo(99L);
        assertThat(event.getState()).isEqualTo(EventState.INIT_CHANNEL);

        verify(publisher).publish(new EventLifecycleEvent.EventChannelReady(eventId));
        verify(eventRepository, atLeast(2)).save(event);
    }

    @Test
    void idempotent_skipsCreateAndPostWhenAlreadyInitialized() throws Exception {
        Event event = new Event();
        event.setId(eventId);
        event.setChannelId(42);
        event.setMessageId(88);

        TextChannel stubChannel = mock(TextChannel.class);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(discordService.getChannel(event)).thenReturn(stubChannel);

        listener.handle(new EventLifecycleEvent.EventCreated(eventId));

        verify(discordService, never()).createEventChannel(any());
        verify(discordService, never()).postEventMessage(any(), any());

        assertThat(event.getState()).isEqualTo(EventState.INIT_CHANNEL);
        verify(publisher).publish(new EventLifecycleEvent.EventChannelReady(eventId));
    }
}
