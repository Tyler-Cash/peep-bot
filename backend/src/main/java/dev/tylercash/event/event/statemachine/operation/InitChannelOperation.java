package dev.tylercash.event.event.statemachine.operation;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InitChannelOperation {

    private final DiscordService discordService;
    private final EventRepository eventRepository;

    public Action<EventState, EventStateMachineEvent> action() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
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
        };
    }
}
