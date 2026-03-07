package dev.tylercash.event.event.statemachine.operation;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import java.time.Clock;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
public class CompleteOperation {

    private final Clock clock;
    private final DiscordService discordService;
    private final EventRepository eventRepository;

    public Guard<EventState, EventStateMachineEvent> guard() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            ZonedDateTime now = ZonedDateTime.now(clock);
            return now.isAfter(event.getDateTime().plusHours(6));
        };
    }

    public Action<EventState, EventStateMachineEvent> action() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            log.info("Completing event (removing buttons): {}", event.getName());
            discordService.removeEventButtons(event);
            event.getNotifications()
                    .add(new Notification(
                            NotificationType.ATTENDANCE_LOCKED,
                            ZonedDateTime.now(clock).toInstant(),
                            0));
            event.setState(EventState.COMPLETED);
            eventRepository.save(event);
        };
    }
}
