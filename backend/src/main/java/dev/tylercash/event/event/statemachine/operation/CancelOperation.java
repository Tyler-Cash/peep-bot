package dev.tylercash.event.event.statemachine.operation;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import java.time.Clock;
import java.time.ZonedDateTime;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

@Log4j2
@Component
public class CancelOperation {

    private final Clock clock;
    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final ObjectProvider<EventService> eventServiceProvider;

    public CancelOperation(
            Clock clock,
            DiscordService discordService,
            EventRepository eventRepository,
            ObjectProvider<EventService> eventServiceProvider) {
        this.clock = clock;
        this.discordService = discordService;
        this.eventRepository = eventRepository;
        this.eventServiceProvider = eventServiceProvider;
    }

    public Action<EventState, EventStateMachineEvent> action() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            log.info("Cancelling event: {}", event.getName());
            event.setName("[CANCELLED] " + event.getName());
            discordService.removeEventButtons(event);
            eventServiceProvider.getObject().populateAttendance(event);
            discordService.updateEventMessage(event);
            discordService.updateChannelName(event);
            discordService.archiveEventChannel(event);
            event.getNotifications()
                    .add(new Notification(
                            NotificationType.ATTENDANCE_LOCKED,
                            ZonedDateTime.now(clock).toInstant(),
                            0));
            event.setState(EventState.ARCHIVED);
            eventRepository.save(event);
        };
    }
}
