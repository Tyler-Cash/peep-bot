package dev.tylercash.event.event.statemachine.operation;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import dev.tylercash.event.immich.ImmichService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InitCompleteOperation {

    private final ImmichService immichService;
    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final ObjectProvider<EventService> eventServiceProvider;

    public Action<EventState, EventStateMachineEvent> action() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            log.info("Completing initialization for event: {}", event.getName());

            try {
                if (event.getImmichAlbumId() == null) {
                    immichService
                            .createAlbum(event.getName(), event.getDescription())
                            .ifPresent(event::setImmichAlbumId);
                }
                if (event.getImmichAlbumId() != null && event.getImmichShareKey() == null) {
                    immichService.createSharedLink(event.getImmichAlbumId()).ifPresent(event::setImmichShareKey);
                }
            } catch (Exception e) {
                log.warn("Album creation failed for event '{}', continuing to PLANNED", event.getName(), e);
            }

            eventServiceProvider.getObject().populateAttendance(event);
            discordService.updateEventMessage(event);
            event.setState(EventState.PLANNED);
            eventRepository.save(event);
        };
    }
}
