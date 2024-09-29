package dev.tylercash.event.event.processor;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

@Log4j2
@Component
public class UpdateScheduledEvent extends ScheduledEventProcessor {
    private final boolean hasRun = false;

    public UpdateScheduledEvent(DiscordService discordService, EventRepository eventRepository) {
        super(discordService, eventRepository);
    }

    @Override
    public void processEvent(Event event) {
        discordService.updateEventMessage(event);
    }
}
