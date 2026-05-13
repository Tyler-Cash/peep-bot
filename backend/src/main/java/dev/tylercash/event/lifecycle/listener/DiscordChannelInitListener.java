package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.lifecycle.DurableEventListener;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Slf4j
public class DiscordChannelInitListener implements DurableEventListener<EventLifecycleEvent.EventCreated> {

    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final EventLifecyclePublisher publisher;
    private final TransactionTemplate transactionTemplate;

    public DiscordChannelInitListener(
            DiscordService discordService,
            EventRepository eventRepository,
            EventLifecyclePublisher publisher,
            PlatformTransactionManager transactionManager) {
        this.discordService = discordService;
        this.eventRepository = eventRepository;
        this.publisher = publisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public String name() {
        return "Discord Channel Init";
    }

    @Override
    public Class<EventLifecycleEvent.EventCreated> eventType() {
        return EventLifecycleEvent.EventCreated.class;
    }

    // No @Transactional on handle: Discord side-effects (channel create, embed post) are
    // irreversible, so the IDs they return must be committed in their own narrow transactions
    // immediately. Wrapping the whole handle in one transaction means an optimistic-lock
    // collision on the final save() rolls back the channel/message IDs, and the @Retryable
    // shim in DurableListenerInvoker then re-creates both in Discord — duplicating the
    // channel and embed for the event.
    @Override
    public void handle(EventLifecycleEvent.EventCreated e) throws Exception {
        Event event = eventRepository.findById(e.eventId()).orElseThrow();
        log.info("Initializing channel for event: {}", event.getName());

        if (event.getChannelId() == 0) {
            TextChannel channel = discordService.createEventChannel(event);
            long channelId = channel.getIdLong();
            eventRepository.updateChannelId(event.getId(), channelId);
            event.setChannelId(channelId);
        }

        if (event.getMessageId() == 0) {
            TextChannel channel = discordService.getChannel(event);
            Message message = discordService.postEventMessage(event, channel);
            long messageId = message.getIdLong();
            long serverId = message.getGuildIdLong();
            eventRepository.updateMessageAndServerId(event.getId(), messageId, serverId);
            event.setMessageId(messageId);
            event.setServerId(serverId);
        }

        discordService.sortActiveChannels();

        // State update and publish must commit together — otherwise a retry could try to
        // re-insert the same outbox rows and fail on the (eventId, type, listener) primary key.
        transactionTemplate.executeWithoutResult(status -> {
            eventRepository.updateState(event.getId(), EventState.INIT_CHANNEL);
            publisher.publish(new EventLifecycleEvent.EventChannelReady(event.getId()));
        });
    }
}
