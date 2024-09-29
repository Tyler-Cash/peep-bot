package dev.tylercash.event.event.processor;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import io.github.resilience4j.ratelimiter.RateLimiter;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

@Log4j2
@Component
public class PreEventNotification extends ScheduledEventProcessor {
    private final RateLimiter notifyEventRoles;
    private final Clock clock;

    public PreEventNotification(DiscordService discordService, EventRepository eventRepository, RateLimiter notifyEventRoles, Clock clock) {
        super(discordService, eventRepository);
        this.notifyEventRoles = notifyEventRoles;
        this.clock = clock;
    }

    boolean shouldEventBeProcessed(Event event) {
        boolean is2hBeforeEventStart = ZonedDateTime.now(clock)
                .minusHours(2)
                .isBefore(event.getDateTime());
        boolean notAfterEventStart = event.getDateTime()
                .plusMinutes(10)
                .isAfter(ZonedDateTime.now(clock));
        boolean hasNotHappened = event.getState().equals(EventState.PLANNED);
        boolean notNotifiedAlready = !event.getNotifications().contains(new Notification(NotificationType.START_OF_EVENT));
        return hasNotHappened && is2hBeforeEventStart && notAfterEventStart && notNotifiedAlready;
    }

    @Override
    public void processEvent(Event event) {
        if (!shouldEventBeProcessed(event)) {
            return;
        }
        notifyEventRoles.executeRunnable(() -> discordService.notifyUsersBeforeEventStarts(event));
    }
}
