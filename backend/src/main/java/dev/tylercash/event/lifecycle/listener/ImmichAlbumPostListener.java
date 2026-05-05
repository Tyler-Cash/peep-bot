package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.immich.ImmichConfiguration;
import dev.tylercash.event.immich.ImmichService;
import dev.tylercash.event.lifecycle.DurableEventListener;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImmichAlbumPostListener implements DurableEventListener<EventLifecycleEvent.EventCompleted> {

    private final ImmichConfiguration immichConfiguration;
    private final ImmichService immichService;
    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final ObjectProvider<EventService> eventServiceProvider;

    @Override
    public String name() {
        return "Immich Album Post";
    }

    @Override
    public Class<EventLifecycleEvent.EventCompleted> eventType() {
        return EventLifecycleEvent.EventCompleted.class;
    }

    @Override
    @Transactional
    public void handle(EventLifecycleEvent.EventCompleted e) throws Exception {
        if (!immichConfiguration.isEnabled()) {
            log.debug("Immich disabled, skipping album post for event {}", e.eventId());
            return;
        }
        Event event = eventRepository.findById(e.eventId()).orElseThrow();
        if (event.getImmichShareKey() == null) {
            log.warn("Event {} has no immich share key; skipping album post", event.getId());
            return;
        }
        // Idempotency: avoid posting the album link twice.
        // Original PostAlbumOperation didn't have this guard because the state machine
        // prevented re-entry. Outbox prevents re-invocation after SUCCESS, but a partial
        // crash could re-fire. There's no "albumLinkPosted" flag on Event today; for v1
        // accept the small risk of a duplicate post on crash mid-execution.
        String albumUrl = immichService.getShareUrl(event.getImmichShareKey());
        log.info("Posting album link for event: {}", event.getName());
        discordService.sendAlbumLink(event, albumUrl);
        eventServiceProvider.getObject().populateAttendance(event);
        discordService.updateEventMessage(event);
        // No state change; no follow-up event.
    }
}
