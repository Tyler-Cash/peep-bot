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
public class PreEventMaybeConfirmationNotification extends ScheduledEventProcessor {
    private final Clock clock;

    public PreEventMaybeConfirmationNotification(DiscordService discordService, EventRepository eventRepository, Clock clock) {
        super(discordService, eventRepository);
        this.clock = clock;
    }

    boolean shouldEventBeProcessed(Event event) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime eventDateTime = event.getDateTime();

        if (now.isAfter(eventDateTime)) {
            return false;
        }

        boolean isSixHoursBefore = now.isAfter(eventDateTime.minusHours(6));
        boolean isEightAmDayOf = now.toLocalDate().equals(eventDateTime.toLocalDate()) && now.getHour() >= 8;

        boolean shouldSendNotification = isSixHoursBefore || isEightAmDayOf;

        boolean hasPlanningState = event.getState().equals(EventState.PLANNED);
        boolean notNotifiedAlready = !event.getNotifications().contains(new Notification(NotificationType.CONFIRM_ATTENDANCE));

        return hasPlanningState && shouldSendNotification && notNotifiedAlready;
    }

    @Override
    public void processEvent(Event event) {
        if (!shouldEventBeProcessed(event)) {
            return;
        }
        discordService.sendMaybeConfirmationMessage(event);
        eventRepository.save(event);
    }

    @Override
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 1000 * 60)
    public void processAll() {
        super.processAll();
    }
}
