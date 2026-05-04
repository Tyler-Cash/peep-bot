package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import dev.tylercash.event.lifecycle.DurableEventListener;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import java.time.Clock;
import java.time.ZonedDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class EventCancelListener implements DurableEventListener<EventLifecycleEvent.EventCancelRequested> {

    private final Clock clock;
    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final ObjectProvider<EventService> eventServiceProvider;
    private final EventLifecyclePublisher publisher;

    public EventCancelListener(
            Clock clock,
            DiscordService discordService,
            EventRepository eventRepository,
            ObjectProvider<EventService> eventServiceProvider,
            EventLifecyclePublisher publisher) {
        this.clock = clock;
        this.discordService = discordService;
        this.eventRepository = eventRepository;
        this.eventServiceProvider = eventServiceProvider;
        this.publisher = publisher;
    }

    @Override
    public String name() {
        return "Event Cancel";
    }

    @Override
    public Class<EventLifecycleEvent.EventCancelRequested> eventType() {
        return EventLifecycleEvent.EventCancelRequested.class;
    }

    @Override
    @Transactional
    public void handle(EventLifecycleEvent.EventCancelRequested e) throws Exception {
        Event event = eventRepository.findById(e.eventId()).orElseThrow();
        log.info("Cancelling event: {}", event.getName());

        // Idempotency: only prepend [CANCELLED] once.
        if (!event.getName().startsWith("[CANCELLED] ")) {
            event.setName("[CANCELLED] " + event.getName());
        }

        if (event.getMessageId() != 0) {
            discordService.removeEventButtons(event);
            eventServiceProvider.getObject().populateAttendance(event);
            discordService.updateEventMessage(event);
        }
        if (event.getChannelId() != 0) {
            discordService.updateChannelName(event);
            discordService.deletePrivateEventChannel(event);
            event.setPrivateChannelId(null);
            discordService.archiveEventChannel(event);
        }
        // Notification.equals is type-only; adding ATTENDANCE_LOCKED to a Set is naturally
        // idempotent (a second add replaces the first).
        event.getNotifications()
                .add(new Notification(
                        NotificationType.ATTENDANCE_LOCKED,
                        ZonedDateTime.now(clock).toInstant(),
                        0));
        event.setState(EventState.CANCELLED);
        eventRepository.save(event);
        publisher.publish(new EventLifecycleEvent.EventCancelled(event.getId()));
    }
}
