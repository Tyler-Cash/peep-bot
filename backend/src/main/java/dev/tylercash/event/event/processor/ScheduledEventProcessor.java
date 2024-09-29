package dev.tylercash.event.event.processor;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Log4j2
@Component
public abstract class ScheduledEventProcessor extends EventProcessor {
    public ScheduledEventProcessor(DiscordService discordService, EventRepository eventRepository) {
        super(discordService, eventRepository);
    }

    /**
     * @return Entities that are needed to be processed
     */
    public Page<Event> getEvents() {
        return eventRepository.findAllByStateEquals(pageable, EventState.PLANNED);
    }
}
