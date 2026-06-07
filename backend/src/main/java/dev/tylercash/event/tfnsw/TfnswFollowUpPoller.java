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
 * Daily 06:00 (Australia/Sydney) sweep of events occurring {@code followUpLeadDays}
 * from now; re-runs the TfNSW noteworthy check, replies with any newly-noteworthy
 * items, and pins the notice so it's prominent in the run-up to the event.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TfnswFollowUpPoller {
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

    private final TfnswEventSnapshotRepository snapshots;
    private final TfnswOrchestrator orchestrator;
    private final TfnswConfiguration cfg;

    @Scheduled(cron = "0 0 6 * * *", zone = "Australia/Sydney")
    @SchedulerLock(name = "tfnswFollowUpPoller", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    public void run() {
        ZonedDateTime from =
                LocalDate.now(SYDNEY).plusDays(cfg.getFollowUpLeadDays()).atStartOfDay(SYDNEY);
        ZonedDateTime to = from.plusDays(1);
        List<UUID> ids = snapshots.findEventIdsForFollowUpCheck(from, to);
        log.info("TfNSW follow-up poller processing {} events for {}", ids.size(), from.toLocalDate());
        for (UUID id : ids) {
            try {
                orchestrator.process(id, true);
            } catch (Exception e) {
                log.warn("TfNSW follow-up failed for event {}: {}", id, e.getMessage());
            }
        }
    }
}
