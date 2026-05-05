package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.lifecycle.DurableEventListener;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DiscordRolesInitListener implements DurableEventListener<EventLifecycleEvent.EventChannelReady> {

    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final EventLifecyclePublisher publisher;

    @Override
    public String name() {
        return "Discord Roles Init";
    }

    @Override
    public Class<EventLifecycleEvent.EventChannelReady> eventType() {
        return EventLifecycleEvent.EventChannelReady.class;
    }

    @Override
    @Transactional
    public void handle(EventLifecycleEvent.EventChannelReady e) throws Exception {
        Event event = eventRepository.findById(e.eventId()).orElseThrow();
        log.info("Creating Discord roles for event: {}", event.getName());

        // createEventRoles matches roles by name in Discord, so re-invocation after a partial
        // crash is safe — duplicate roles will not be created.
        discordService.createEventRoles(event);

        if (event.getCreator() != null && !event.getCreator().isBlank()) {
            try {
                discordService.assignEventRole(event, event.getCreator(), AttendanceStatus.ACCEPTED);
            } catch (Exception ex) {
                log.warn("Failed to assign accepted role to creator {}", event.getCreator(), ex);
            }
        }

        event.setState(EventState.INIT_ROLES);
        eventRepository.save(event);
        publisher.publish(new EventLifecycleEvent.EventRolesReady(event.getId()));
    }
}
