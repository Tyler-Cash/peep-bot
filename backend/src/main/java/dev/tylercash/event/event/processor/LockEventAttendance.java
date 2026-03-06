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
public class LockEventAttendance extends ScheduledEventProcessor {
    private final Clock clock;

    public LockEventAttendance(DiscordService discordService, EventRepository eventRepository, Clock clock) {
        super(discordService, eventRepository);
        this.clock = clock;
    }

    @Override
    @Scheduled(fixedRate = 1000 * 60)
    @SchedulerLock(name = "lockEventAttendance")
    public void processAll() {
        super.processAll();
    }

    boolean shouldEventBeProcessed(Event event) {
        boolean afterSixHours = ZonedDateTime.now(clock).isAfter(event.getDateTime().plusHours(6));
        boolean hasPlanningState = event.getState().equals(EventState.PLANNED);
        boolean notLockedAlready = !event.getNotifications().contains(new Notification(NotificationType.ATTENDANCE_LOCKED));
        return afterSixHours && hasPlanningState && notLockedAlready;
    }

    @Override
    public void processEvent(Event event) {
        if (!shouldEventBeProcessed(event)) {
            return;
        }
        log.info("Locking attendance for event: {}", event.getName());
        discordService.removeEventButtons(event);
        event.getNotifications().add(
                new Notification(
                        NotificationType.ATTENDANCE_LOCKED,
                        ZonedDateTime.now(clock).toInstant(),
                        0
                ));
        eventRepository.save(event);
    }
}
