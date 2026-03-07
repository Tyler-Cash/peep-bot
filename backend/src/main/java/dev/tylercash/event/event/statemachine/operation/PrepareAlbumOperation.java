package dev.tylercash.event.event.statemachine.operation;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import dev.tylercash.event.immich.ImmichConfiguration;
import dev.tylercash.event.immich.ImmichService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
public class PrepareAlbumOperation {

    private final ImmichConfiguration immichConfiguration;
    private final ImmichService immichService;
    private final EventRepository eventRepository;

    public Guard<EventState, EventStateMachineEvent> guard() {
        return context -> immichConfiguration.isEnabled();
    }

    public Action<EventState, EventStateMachineEvent> action() {
        return context -> {
            Event event = context.getExtendedState().get("event", Event.class);
            boolean progressMade = false;

            if (event.getImmichAlbumId() == null) {
                immichService
                        .createAlbum(event.getName(), event.getDescription())
                        .ifPresent(event::setImmichAlbumId);
                progressMade = event.getImmichAlbumId() != null;
            }

            if (event.getImmichAlbumId() != null && event.getImmichShareKey() == null) {
                immichService.createSharedLink(event.getImmichAlbumId()).ifPresent(event::setImmichShareKey);
                progressMade = progressMade || event.getImmichShareKey() != null;
            }

            if (event.getImmichAlbumId() != null && event.getImmichShareKey() != null) {
                log.info(
                        "Album prepared for event '{}': albumId={}, shareKey={}",
                        event.getName(),
                        event.getImmichAlbumId(),
                        event.getImmichShareKey());
                event.setState(EventState.ALBUM_READY);
                eventRepository.save(event);
                return;
            }

            log.warn(
                    "Album preparation incomplete for event '{}': albumId={}, shareKey={}",
                    event.getName(),
                    event.getImmichAlbumId(),
                    event.getImmichShareKey());
            if (progressMade) {
                eventRepository.save(event);
            }
        };
    }
}
