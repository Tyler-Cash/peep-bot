package dev.tylercash.event.lifecycle.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class DiscordChannelInitListenerTest {

    private DiscordService discordService;
    private EventRepository eventRepository;
    private EventLifecyclePublisher publisher;
    private PlatformTransactionManager transactionManager;
    private DiscordChannelInitListener listener;

    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordService.class);
        eventRepository = mock(EventRepository.class);
        publisher = mock(EventLifecyclePublisher.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        listener = new DiscordChannelInitListener(discordService, eventRepository, publisher, transactionManager);
    }

    @Test
    void fullInit_persistsChannelAndMessageIdsViaNarrowUpdatesAndPublishes() throws Exception {
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

        // Each Discord ID is persisted via its own narrow REQUIRES_NEW update so it survives
        // a later optimistic-lock rollback — verifying these calls is the regression guard
        // against the duplicate-channel/duplicate-embed race.
        verify(eventRepository).updateChannelId(eventId, 42L);
        verify(eventRepository).updateMessageAndServerId(eventId, 88L, 99L);
        verify(eventRepository).updateState(eventId, EventState.INIT_CHANNEL);
        verify(eventRepository, never()).save(any());
        verify(publisher).publish(new EventLifecycleEvent.EventChannelReady(eventId));
    }

    @Test
    void idempotent_skipsCreateAndPostWhenAlreadyInitialized() throws Exception {
        Event event = new Event();
        event.setId(eventId);
        event.setChannelId(42);
        event.setMessageId(88);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        listener.handle(new EventLifecycleEvent.EventCreated(eventId));

        verify(discordService, never()).createEventChannel(any());
        verify(discordService, never()).postEventMessage(any(), any());
        verify(eventRepository, never()).updateChannelId(any(), anyLong());
        verify(eventRepository, never()).updateMessageAndServerId(any(), anyLong(), anyLong());

        verify(eventRepository).updateState(eventId, EventState.INIT_CHANNEL);
        verify(publisher).publish(new EventLifecycleEvent.EventChannelReady(eventId));
    }

    @Test
    void retryAfterPartialInit_doesNotRecreateDiscordResources() throws Exception {
        // Simulates the previously-broken scenario: first attempt persisted channelId via
        // REQUIRES_NEW, then the surrounding work failed and the listener is retried. The
        // re-loaded Event must reflect the committed channelId so we skip Discord creation.
        Event event = new Event();
        event.setId(eventId);
        event.setChannelId(42);
        event.setMessageId(0);

        TextChannel stubChannel = mock(TextChannel.class);
        Message stubMessage = mock(Message.class);
        when(stubMessage.getGuildIdLong()).thenReturn(99L);
        when(stubMessage.getIdLong()).thenReturn(88L);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(discordService.getChannel(event)).thenReturn(stubChannel);
        when(discordService.postEventMessage(eq(event), any(TextChannel.class))).thenReturn(stubMessage);

        listener.handle(new EventLifecycleEvent.EventCreated(eventId));

        verify(discordService, never()).createEventChannel(any());
        verify(eventRepository, never()).updateChannelId(any(), anyLong());
        verify(eventRepository).updateMessageAndServerId(eventId, 88L, 99L);
        assertThat(event.getMessageId()).isEqualTo(88L);
    }
}
