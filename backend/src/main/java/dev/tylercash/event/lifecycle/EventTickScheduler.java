package dev.tylercash.event.lifecycle;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventTickScheduler {

    // Lifecycle timing:
    //   POST_COMPLETED → ARCHIVED  at 10:00 (event zone) on completion-date + 2 days.
    //                              completion-date is the date of event.dateTime + 6h. Constant
    //                              for all guilds — "the day after the day after the event ends".
    //   ARCHIVED → DELETED         archive_days after the archive moment (per-guild setting,
    //                              default 90, minimum 7). The archived channel is deleted then;
    //                              the event row + attendance + roles persist beyond that, only
    //                              the channel and roles are removed by the DELETE listener.
    private static final int COMPLETION_TO_ARCHIVE_DAYS = 2;
    private static final int SHORTEST_ARCHIVE_DAYS = 7;
    private static final int DEFAULT_ARCHIVE_DAYS = 90;
    private static final LocalTime ARCHIVE_TIME_OF_DAY = LocalTime.of(10, 0);

    private final EventRepository events;
    private final EventTickLogRepository tickLog;
    private final EventLifecyclePublisher publisher;
    private final Clock clock;
    private final GuildRepository guildRepository;

    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "EventTickScheduler", lockAtMostFor = "PT5M")
    @Transactional
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

        // ARCHIVAL: archive at 10:00 (event-zone) on completion-date + 2 days. Constant.
        emitArchivalTicks(now);

        // DELETE:
        //   CANCELLED → DELETED: 3 months after event.dateTime (cancelled events skip the archive
        //                        category entirely, so archive_days does not apply).
        //   ARCHIVED  → DELETED: guild.archive_days after the archive moment.
        emitCancelledDeletionTicks(now);
        emitArchivedDeletionTicks(now);
    }

    private void emitArchivalTicks(ZonedDateTime now) {
        // The earliest possible archive-due moment is completion-date + 2 days at 10:00. Any event
        // whose dateTime is within the last ~2 days cannot be due yet.
        ZonedDateTime from = now.minusYears(10);
        ZonedDateTime to = now.minusDays(COMPLETION_TO_ARCHIVE_DAYS);
        for (Event e : events.findInDateWindow(from, to, EventState.POST_COMPLETED)) {
            if (now.isBefore(archiveDueAt(e))) {
                continue;
            }
            recordAndPublishTick(e, "ARCHIVAL", () -> new EventLifecycleEvent.EventArchivalDue(e.getId()));
        }
    }

    private void emitArchivedDeletionTicks(ZonedDateTime now) {
        // Candidate window: events whose dateTime is older than (2 + minimum archive_days) days.
        // Anything more recent cannot have spent enough time in the archived category yet.
        ZonedDateTime from = now.minusYears(10);
        ZonedDateTime to = now.minusDays(COMPLETION_TO_ARCHIVE_DAYS + SHORTEST_ARCHIVE_DAYS);
        for (Event e : events.findInDateWindow(from, to, EventState.ARCHIVED)) {
            int archiveDays = guildRepository
                    .findById(e.getServerId())
                    .map(g -> g.getArchiveDays() == 0 ? DEFAULT_ARCHIVE_DAYS : g.getArchiveDays())
                    .orElse(DEFAULT_ARCHIVE_DAYS);
            ZonedDateTime deleteAt = archiveDueAt(e).plusDays(archiveDays);
            if (now.isBefore(deleteAt)) {
                continue;
            }
            recordAndPublishTick(e, "DELETE_ARCHIVED", () -> new EventLifecycleEvent.EventDeleteRequested(e.getId()));
        }
    }

    private void emitCancelledDeletionTicks(ZonedDateTime now) {
        // Cancelled events skip the archive category — retention is the historical 3 months from
        // event.dateTime, regardless of guild.archive_days.
        ZonedDateTime to = now.minusMonths(3);
        emitTick(
                "DELETE_CANCELLED",
                now.minusYears(10),
                to,
                EventState.CANCELLED,
                EventLifecycleEvent.EventDeleteRequested::new);
    }

    private void recordAndPublishTick(Event e, String tickType, java.util.function.Supplier<EventLifecycleEvent> ctor) {
        EventTickLogId id = new EventTickLogId(e.getId(), tickType);
        if (tickLog.existsById(id)) return;
        EventTickLog row = new EventTickLog();
        row.setEventId(e.getId());
        row.setTickType(tickType);
        tickLog.save(row);
        try {
            publisher.publish(ctor.get());
        } catch (Exception ex) {
            log.error("Failed to publish {} for event {}", tickType, e.getId(), ex);
        }
    }

    private static ZonedDateTime archiveDueAt(Event e) {
        ZonedDateTime completion = e.getDateTime().plusHours(6);
        return completion
                .toLocalDate()
                .plusDays(COMPLETION_TO_ARCHIVE_DAYS)
                .atTime(ARCHIVE_TIME_OF_DAY)
                .atZone(completion.getZone());
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
}
