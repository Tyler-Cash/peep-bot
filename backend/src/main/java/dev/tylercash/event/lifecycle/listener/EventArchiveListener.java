package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUtil;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.lifecycle.DurableEventListener;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class EventArchiveListener implements DurableEventListener<EventLifecycleEvent.EventArchivalDue> {

    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final ObjectProvider<EventService> eventServiceProvider;
    private final EventLifecyclePublisher publisher;

    public EventArchiveListener(
            DiscordService discordService,
            EventRepository eventRepository,
            ObjectProvider<EventService> eventServiceProvider,
            EventLifecyclePublisher publisher) {
        this.discordService = discordService;
        this.eventRepository = eventRepository;
        this.eventServiceProvider = eventServiceProvider;
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "Event Archive";
    }

    @Override
    public Class<EventLifecycleEvent.EventArchivalDue> eventType() {
        return EventLifecycleEvent.EventArchivalDue.class;
    }

    @Override
    @Transactional
    public void handle(EventLifecycleEvent.EventArchivalDue e) throws Exception {
        Event event = eventRepository.findById(e.eventId()).orElseThrow();
        // No precise re-check here: EventTickScheduler is the single source of timing.
        // It already filters by the guild's configured archive_days before publishing, so
        // by the time we receive the event it is genuinely due.

        String eventName = DiscordUtil.getChannelNameFromEvent(event);
        log.info("Archiving event: {}", eventName);
        eventServiceProvider.getObject().populateAttendance(event);
        discordService.updateEventMessage(event);
        discordService.deletePrivateEventChannel(event);
        event.setPrivateChannelId(null);
        discordService.archiveEventChannel(event);
        event.setState(EventState.ARCHIVED);
        eventRepository.save(event);
        publisher.publish(new EventLifecycleEvent.EventArchived(event.getId()));
    }
}
