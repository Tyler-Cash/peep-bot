package dev.tylercash.event.event.processor;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

@Log4j2
@Component
public class PreEventNotification extends ScheduledEventProcessor {
    private final Clock clock;

    public PreEventNotification(DiscordService discordService, EventRepository eventRepository, Clock clock) {
        super(discordService, eventRepository);
        this.clock = clock;
    }

    boolean shouldEventBeProcessed(Event event) {
        boolean twoHoursBeforeEvent = ZonedDateTime.now(clock).isAfter(event.getDateTime().minusHours(2));
        boolean beforeEvent = ZonedDateTime.now(clock).isBefore(event.getDateTime());
        boolean isAtMost2hBeforeEventStart = twoHoursBeforeEvent && beforeEvent;
        boolean hasPlanningState = event.getState().equals(EventState.PLANNED);
        boolean notNotifiedAlready = !event.getNotifications().contains(new Notification(NotificationType.START_OF_EVENT));
        return hasPlanningState && isAtMost2hBeforeEventStart && notNotifiedAlready;
    }

    @Override
    public void processEvent(Event event) {
        if (!shouldEventBeProcessed(event)) {
            return;
        }
        discordService.sendMessageBeforeEvent(event);
        eventRepository.save(event);
    }
}
