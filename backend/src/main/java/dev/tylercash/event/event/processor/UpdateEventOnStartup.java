package dev.tylercash.event.event.processor;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

@Log4j2
@Component
public class UpdateEventOnStartup extends ScheduledEventProcessor {
    private boolean hasRun = false;

    public UpdateEventOnStartup(DiscordService discordService, EventRepository eventRepository) {
        super(discordService, eventRepository);
    }

    @Override
    public void processEvent(Event event) {
        if (hasRun) {
            return;
        }
        discordService.updateEventMessage(event);
        hasRun = true;
    }
}
