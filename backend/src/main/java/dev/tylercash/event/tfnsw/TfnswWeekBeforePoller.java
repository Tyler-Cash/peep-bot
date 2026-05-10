package dev.tylercash.event.tfnsw;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily 06:00 (Australia/Sydney) sweep of events occurring 7 days from now;
 * re-runs the TfNSW noteworthy check and posts an "Update:" embed only when
 * the snapshot hash differs from the original create-time check.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TfnswWeekBeforePoller {
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

    private final TfnswEventSnapshotRepository snapshots;
    private final TfnswOrchestrator orchestrator;

    @Scheduled(cron = "0 0 6 * * *", zone = "Australia/Sydney")
    @SchedulerLock(name = "tfnswWeekBeforePoller", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void run() {
        ZonedDateTime from = LocalDate.now(SYDNEY).plusDays(7).atStartOfDay(SYDNEY);
        ZonedDateTime to = from.plusDays(1);
        List<UUID> ids = snapshots.findEventIdsForWeekBeforeCheck(from, to);
        log.info("TfNSW week-before poller processing {} events for {}", ids.size(), from.toLocalDate());
        for (UUID id : ids) {
            try {
                orchestrator.process(id, true);
            } catch (Exception e) {
                log.warn("TfNSW week-before failed for event {}: {}", id, e.getMessage());
            }
        }
    }
}
