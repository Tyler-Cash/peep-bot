package dev.tylercash.event.lifecycle;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventTickScheduler {

    private final EventRepository events;
    private final EventTickLogRepository tickLog;
    private final EventLifecyclePublisher publisher;
    private final Clock clock;

    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "EventTickScheduler", lockAtMostFor = "PT5M")
    public void emit() {
        ZonedDateTime now = ZonedDateTime.now(clock);

        // PRE_NOTIFY: guard is now > event.dateTime - 2h && now < event.dateTime
        // → event.dateTime is in the window (now, now+2h), state PLANNED
        emitTick("PRE_NOTIFY", now, now.plusHours(2), EventState.PLANNED, EventLifecycleEvent.EventPreNotifyDue::new);

        // COMPLETION: guard is now > event.dateTime + 6h
        // → event.dateTime < now - 6h, state PRE_NOTIFIED
        emitTick(
                "COMPLETION",
                now.minusYears(10),
                now.minusHours(6),
                EventState.PRE_NOTIFIED,
                EventLifecycleEvent.EventCompletionDue::new);

        // ARCHIVAL: guard is now > event.dateTime.plusDays(1)@22:00:00
        // The exact threshold depends on the time-of-day of the event's dateTime, which cannot be
        // expressed as a simple BETWEEN in JPQL. We use a conservative approximation: emit for all
        // POST_COMPLETED events whose dateTime is more than 22 hours ago. Borderline events (dateTime
        // 22–24h ago whose archiveTime hasn't hit 22:00 yet) may be emitted slightly early; the
        // ArchiveOperation guard inside the listener will reject and retry until the threshold passes.
        emitTick(
                "ARCHIVAL",
                now.minusYears(10),
                now.minusHours(22),
                EventState.POST_COMPLETED,
                EventLifecycleEvent.EventArchivalDue::new);

        // DELETE: guard is now > event.dateTime + 3 months
        // → event.dateTime < now - 3 months, applies to both CANCELLED and ARCHIVED states
        emitDeletionTick(now, EventState.CANCELLED);
        emitDeletionTick(now, EventState.ARCHIVED);
    }

    private void emitTick(
            String tickType,
            ZonedDateTime from,
            ZonedDateTime to,
            EventState state,
            Function<UUID, EventLifecycleEvent> ctor) {
        for (Event e : events.findInDateWindow(from, to, state)) {
            EventTickLogId id = new EventTickLogId(e.getId(), tickType);
            if (tickLog.existsById(id)) continue;
            EventTickLog row = new EventTickLog();
            row.setEventId(e.getId());
            row.setTickType(tickType);
            tickLog.save(row);
            try {
                publisher.publish(ctor.apply(e.getId()));
            } catch (Exception ex) {
                log.error("Failed to publish {} for event {}", tickType, e.getId(), ex);
            }
        }
    }

    private void emitDeletionTick(ZonedDateTime now, EventState state) {
        // DELETE guard: now > event.dateTime + 3 months → event.dateTime < now - 3 months
        ZonedDateTime to = now.minusMonths(3);
        String tickType = "DELETE_" + state.name();
        emitTick(tickType, now.minusYears(10), to, state, EventLifecycleEvent.EventDeleteRequested::new);
    }
}
