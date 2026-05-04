package dev.tylercash.event.lifecycle.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.event.model.Notification;
import dev.tylercash.event.event.model.NotificationType;
import dev.tylercash.event.lifecycle.DurableEventListener;
import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.EventLifecyclePublisher;
import java.time.Clock;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventCompleteListener implements DurableEventListener<EventLifecycleEvent.EventCompletionDue> {

    private final Clock clock;
    private final DiscordService discordService;
    private final EventRepository eventRepository;
    private final EventLifecyclePublisher publisher;

    @Override
    public String name() {
        return "Event Complete";
    }

    @Override
    public Class<EventLifecycleEvent.EventCompletionDue> eventType() {
        return EventLifecycleEvent.EventCompletionDue.class;
    }

    /**
     * Removes event buttons from Discord, records an ATTENDANCE_LOCKED notification, transitions
     * the event to POST_COMPLETED, and publishes EventCompleted.
     *
     * <p>Idempotency note: {@code removeEventButtons} is idempotent at the Discord side — button
     * removal is a no-op if buttons are already gone. Adding a Notification on retry would create a
     * duplicate notification; however, the outbox prevents re-invocation once this listener reaches
     * SUCCESS, and the crash window between Discord acceptance and transaction commit is small. A
     * duplicate ATTENDANCE_LOCKED entry is therefore accepted as a vanishingly rare edge case.
     */
    @Override
    @Transactional
    public void handle(EventLifecycleEvent.EventCompletionDue e) throws Exception {
        Event event = eventRepository.findById(e.eventId()).orElseThrow();
        log.info("Completing event (removing buttons): {}", event.getName());
        discordService.removeEventButtons(event);
        event.getNotifications()
                .add(new Notification(
                        NotificationType.ATTENDANCE_LOCKED,
                        ZonedDateTime.now(clock).toInstant(),
                        0));
        event.setState(EventState.POST_COMPLETED);
        eventRepository.save(event);
        publisher.publish(new EventLifecycleEvent.EventCompleted(event.getId()));
    }
}
