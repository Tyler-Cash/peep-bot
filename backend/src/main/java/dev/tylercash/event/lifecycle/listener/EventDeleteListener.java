package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUtil;
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
public class EventDeleteListener implements DurableEventListener<EventLifecycleEvent.EventDeleteRequested> {

    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final EventLifecyclePublisher publisher;

    @Override
    public String name() {
        return "Event Delete";
    }

    @Override
    public Class<EventLifecycleEvent.EventDeleteRequested> eventType() {
        return EventLifecycleEvent.EventDeleteRequested.class;
    }

    @Override
    @Transactional
    public void handle(EventLifecycleEvent.EventDeleteRequested e) throws Exception {
        Event event = eventRepository.findById(e.eventId()).orElseThrow();
        String eventName = DiscordUtil.getChannelNameFromEvent(event);
        log.info("Deleting event: {}", eventName);
        discordService.deleteEventRoles(event);
        discordService.deleteEventChannel(event);
        event.setState(EventState.DELETED);
        eventRepository.save(event);
        publisher.publish(new EventLifecycleEvent.EventDeleted(event.getId()));
    }
}
