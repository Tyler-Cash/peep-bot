package dev.tylercash.event.event.statemachine.operation;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUtil;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import java.time.Clock;
import java.time.ZonedDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ArchiveOperation {

    private final Clock clock;
    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final ObjectProvider<EventService> eventServiceProvider;

    public ArchiveOperation(
            Clock clock,
            DiscordService discordService,
            EventRepository eventRepository,
            ObjectProvider<EventService> eventServiceProvider) {
        this.clock = clock;
        this.discordService = discordService;
        this.eventRepository = eventRepository;
        this.eventServiceProvider = eventServiceProvider;
    }

    public Guard<EventState, EventStateMachineEvent> guard() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            ZonedDateTime now = ZonedDateTime.now(clock);
            ZonedDateTime archiveTime = event.getDateTime()
                    .plusDays(1)
                    .withHour(22)
                    .withMinute(0)
                    .withSecond(0)
                    .withNano(0);
            return now.isAfter(archiveTime);
        };
    }

    public Action<EventState, EventStateMachineEvent> action() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            String eventName = DiscordUtil.getChannelNameFromEvent(event);
            log.info("Archiving event: {}", eventName);
            eventServiceProvider.getObject().populateAttendance(event);
            discordService.updateEventMessage(event);
            discordService.archiveEventChannel(event);
            event.setState(EventState.ARCHIVED);
            eventRepository.save(event);
        };
    }
}
