package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.lifecycle.DurableEventListener;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DiscordChannelInitListener implements DurableEventListener<EventLifecycleEvent.EventCreated> {

    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final EventLifecyclePublisher publisher;

    @Override
    public String name() {
        return "Discord Channel Init";
    }

    @Override
    public Class<EventLifecycleEvent.EventCreated> eventType() {
        return EventLifecycleEvent.EventCreated.class;
    }

    @Override
    @Transactional
    public void handle(EventLifecycleEvent.EventCreated e) throws Exception {
        Event event = eventRepository.findById(e.eventId()).orElseThrow();
        log.info("Initializing channel for event: {}", event.getName());

        if (event.getChannelId() == 0) {
            TextChannel channel = discordService.createEventChannel(event);
            event.setChannelId(channel.getIdLong());
            eventRepository.save(event);
        }

        if (event.getMessageId() == 0) {
            TextChannel channel = discordService.getChannel(event);
            Message message = discordService.postEventMessage(event, channel);
            event.setServerId(message.getGuildIdLong());
            event.setMessageId(message.getIdLong());
        }

        discordService.sortActiveChannels();
        event.setState(EventState.INIT_CHANNEL);
        eventRepository.save(event);

        publisher.publish(new EventLifecycleEvent.EventChannelReady(event.getId()));
    }
}
