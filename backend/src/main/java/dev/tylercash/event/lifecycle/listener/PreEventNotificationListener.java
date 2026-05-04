package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
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
public class PreEventNotificationListener implements DurableEventListener<EventLifecycleEvent.EventPreNotifyDue> {

    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final ObjectProvider<EventService> eventServiceProvider;
    private final EventLifecyclePublisher publisher;

    public PreEventNotificationListener(
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
        return "Pre-Event Notification";
    }

    @Override
    public Class<EventLifecycleEvent.EventPreNotifyDue> eventType() {
        return EventLifecycleEvent.EventPreNotifyDue.class;
    }

    /**
     * Sends the pre-event notification message to Discord, transitions the event to
     * PRE_NOTIFIED, and publishes EventPreNotified.
     *
     * <p>Idempotency note: {@code populateAttendance} is safe to repeat. However,
     * {@code sendMessageBeforeEvent} posts a Discord message — a crash between Discord
     * accepting the post and the transaction committing would cause a duplicate message
     * on retry. This is accepted: the listener-level outbox (SUCCESS row) combined with
     * the EventTickScheduler's tick log makes a double-fire vanishingly unlikely.
     */
    @Override
    @Transactional
    public void handle(EventLifecycleEvent.EventPreNotifyDue e) throws Exception {
        Event event = eventRepository.findById(e.eventId()).orElseThrow();
        log.info("Sending pre-event notification for: {}", event.getName());
        eventServiceProvider.getObject().populateAttendance(event);
        discordService.sendMessageBeforeEvent(event);
        event.setState(EventState.PRE_NOTIFIED);
        eventRepository.save(event);
        publisher.publish(new EventLifecycleEvent.EventPreNotified(event.getId()));
    }
}
