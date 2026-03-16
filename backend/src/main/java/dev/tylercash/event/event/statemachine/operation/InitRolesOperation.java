package dev.tylercash.event.event.statemachine.operation;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InitRolesOperation {

    private final DiscordService discordService;
    private final EventRepository eventRepository;

    public Action<EventState, EventStateMachineEvent> action() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            log.info("Creating Discord roles for event: {}", event.getName());
            discordService.createEventRoles(event);
            event.setState(EventState.INIT_ROLES);
            eventRepository.save(event);
        };
    }
}
