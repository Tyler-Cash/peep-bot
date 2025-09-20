package dev.tylercash.event.event.processor;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import lombok.extern.log4j.Log4j2;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

@Log4j2
@Component
public class MaybeConfirmationNotification extends ScheduledEventProcessor {
    private final Clock clock;

    public MaybeConfirmationNotification(DiscordService discordService, EventRepository eventRepository, Clock clock) {
        super(discordService, eventRepository);
        this.clock = clock;
    }

    @Override
    @Scheduled(fixedRate = 1000 * 60)
    @SchedulerLock(name = "maybeConfirmationNotification")
    public void processAll() {
        super.processAll();
    }

    boolean shouldEventBeProcessed(Event event) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime eventTime = event.getDateTime();

        boolean isSixHoursBefore = now.isAfter(eventTime.minusHours(6)) && now.isBefore(eventTime);
        boolean is8AmDayOf = now.toLocalDate().equals(eventTime.toLocalDate()) && now.getHour() >= 8;

        boolean shouldProcessTime = isSixHoursBefore || is8AmDayOf;

        boolean hasPlanningState = event.getState().equals(EventState.PLANNED);
        boolean notNotifiedAlready = !event.getNotifications().contains(new Notification(NotificationType.MAYBE_CONFIRMATION));

        return hasPlanningState && shouldProcessTime && notNotifiedAlready;
    }

    @Override
    public void processEvent(Event event) {
        if (!shouldEventBeProcessed(event)) {
            return;
        }
        discordService.sendMaybeConfirmationMessage(event);
        eventRepository.save(event);
    }
}
