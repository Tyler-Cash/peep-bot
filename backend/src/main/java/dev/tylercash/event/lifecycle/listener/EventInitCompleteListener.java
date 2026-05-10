package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.immich.ImmichService;
import dev.tylercash.event.lifecycle.DurableEventListener;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventInitCompleteListener implements DurableEventListener<EventLifecycleEvent.EventClassified> {

    private final ImmichService immichService;
    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final ObjectProvider<EventService> eventServiceProvider;
    private final EventLifecyclePublisher publisher;

    @Override
    public String name() {
        return "Event Init Complete";
    }

    @Override
    public Class<EventLifecycleEvent.EventClassified> eventType() {
        return EventLifecycleEvent.EventClassified.class;
    }

    @Override
    @Transactional
    public void handle(EventLifecycleEvent.EventClassified e) throws Exception {
        Event event = eventRepository.findById(e.eventId()).orElseThrow();
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
        } catch (Exception ex) {
            log.warn("Album creation failed for event '{}', continuing to PLANNED", event.getName(), ex);
        }

        eventServiceProvider.getObject().populateAttendance(event);
        discordService.updateEventMessage(event);
        // Targeted updates: this listener owns immichAlbumId, immichShareKey, and
        // state. Other fields (name, description, location, …) may have been
        // mutated by an HTTP handler while the Immich + Discord calls above were
        // in flight; a blind save() would overwrite them with the snapshot we
        // loaded at the top of this method.
        eventRepository.updateImmichAlbumDetails(event.getId(), event.getImmichAlbumId(), event.getImmichShareKey());
        eventRepository.updateState(event.getId(), EventState.PLANNED);
        publisher.publish(new EventLifecycleEvent.EventPlanned(event.getId()));
    }
}
