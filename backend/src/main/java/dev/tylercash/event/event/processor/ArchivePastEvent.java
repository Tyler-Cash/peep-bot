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
public class ArchivePastEvent extends ScheduledEventProcessor {
    private final Clock clock;

    public ArchivePastEvent(DiscordService discordService, EventRepository eventRepository, Clock clock) {
        super(discordService, eventRepository);
        this.clock = clock;
    }


    @Override
    public void processEvent(Event event) {
        if (event.getState() != EventState.PLANNED) {
            return;
        }
        ZonedDateTime eventExpiry = event.getDateTime()
                .plusDays(1).withHour(22).withMinute(0).withSecond(0).withNano(0);
        String eventName = DiscordUtil.getChannelNameFromEvent(event);
        if (ZonedDateTime.now(clock).isAfter(eventExpiry)) {
            try {
                discordService.archiveEventChannel(event);
                event.setState(EventState.ARCHIVED);
                eventRepository.save(event);
                log.info("Event {} has been archived", eventName);
            } catch (Exception e) {
                log.error("Error archiving event {}", eventName, e);
            }
        }
    }

    @Override
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 1000 * 60)
    public void processAll() {
        super.processAll();
    }
}
