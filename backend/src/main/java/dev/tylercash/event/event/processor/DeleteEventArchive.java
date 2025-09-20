package dev.tylercash.event.event.processor;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUtil;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

@Log4j2
@Component
public class DeleteEventArchive extends ArchivedEventProcessor {
    private final Clock clock;

    public DeleteEventArchive(DiscordService discordService, EventRepository eventRepository, Clock clock) {
        super(discordService, eventRepository);
        this.clock = clock;
    }


    @Override
    public void processEvent(Event event) {
        if (event.getState() != EventState.ARCHIVED) {
            return;
        }
        ZonedDateTime eventExpiry = event.getDateTime().plusMonths(3);
        String eventName = DiscordUtil.getChannelNameFromEvent(event);
        if (ZonedDateTime.now(clock).isAfter(eventExpiry)) {
            try {
                discordService.deleteEventChannel(event);
                event.setState(EventState.DELETED);
                eventRepository.save(event);
                log.info("Event {} has been deleted", eventName);
            } catch (Exception e) {
                log.error("Error deleting event {}", eventName, e);
            }
        }
    }

    @Override
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 1000 * 60)
    public void processAll() {
        super.processAll();
    }
}
