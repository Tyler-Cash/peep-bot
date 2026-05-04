package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.immich.ImmichConfiguration;
import dev.tylercash.event.immich.ImmichService;
import dev.tylercash.event.lifecycle.DurableEventListener;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImmichAlbumPrepListener implements DurableEventListener<EventLifecycleEvent.EventPreNotified> {

    private final ImmichConfiguration immichConfiguration;
    private final ImmichService immichService;
    private final EventRepository eventRepository;

    @Override
    public String name() {
        return "Immich Album Prep";
    }

    @Override
    public Class<EventLifecycleEvent.EventPreNotified> eventType() {
        return EventLifecycleEvent.EventPreNotified.class;
    }

    @Override
    @Transactional
    public void handle(EventLifecycleEvent.EventPreNotified e) throws Exception {
        if (!immichConfiguration.isEnabled()) {
            log.debug("Immich disabled, skipping album prep for event {}", e.eventId());
            return;
        }
        Event event = eventRepository.findById(e.eventId()).orElseThrow();
        // Idempotency: skip work that's already done.
        if (event.getImmichAlbumId() != null && event.getImmichShareKey() != null) {
            log.debug("Album already prepared for event '{}', skipping", event.getName());
            return;
        }
        if (event.getImmichAlbumId() == null) {
            immichService.createAlbum(event.getName(), event.getDescription()).ifPresent(event::setImmichAlbumId);
        }
        if (event.getImmichAlbumId() != null && event.getImmichShareKey() == null) {
            immichService.createSharedLink(event.getImmichAlbumId()).ifPresent(event::setImmichShareKey);
        }
        eventRepository.save(event);
        if (event.getImmichAlbumId() != null && event.getImmichShareKey() != null) {
            log.info(
                    "Album prepared for event '{}': albumId={}, shareKey={}",
                    event.getName(),
                    event.getImmichAlbumId(),
                    event.getImmichShareKey());
        } else {
            // Throw to signal failure — the dispatcher will record FAILED + retry.
            throw new IllegalStateException("Album preparation incomplete for event " + event.getId()
                    + ": albumId=" + event.getImmichAlbumId()
                    + ", shareKey=" + event.getImmichShareKey());
        }
    }
}
