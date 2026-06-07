package dev.tylercash.event.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Static catalog of the project's scheduled jobs and reactive listeners, joined with live
 * shedlock state. The catalog itself is hand-curated — Spring's @Scheduled metadata isn't
 * introspectable at runtime in any portable way, so the alternative is a registry no one
 * remembers to update. This is a small list and rarely changes.
 *
 * <p>Reactive listener entries (cron == "(reactive)") have no shedlock row by design;
 * lastRun / nextRun stay null and the UI renders them as "—".
 */
@Component
@Slf4j
public class AdminJobCatalog {

    private record CatalogEntry(String id, String label, String cron, String emits, String shedLockName) {}

    private static final List<CatalogEntry> CATALOG = List.of(
            new CatalogEntry(
                    "event-tick",
                    "EventTickScheduler",
                    "0 * * * * *",
                    "EventPreNotifyDue · EventCompletionDue · EventArchivalDue · EventDeleteRequested",
                    "EventTickScheduler"),
            new CatalogEntry(
                    "outbox-retry",
                    "DurableListenerRetryPoller · retry",
                    "every 60s",
                    "—",
                    "DurableListenerRetryPoller"),
            new CatalogEntry(
                    "outbox-stuck-gauge",
                    "DurableListenerRetryPoller · stuck-gauge",
                    "every 1h",
                    "—",
                    "DurableListenerStuckCounter"),
            new CatalogEntry(
                    "categorization-retry", "CategorizationRetryService", "every 60s", "—", "categorizationRetry"),
            new CatalogEntry(
                    "embedding-backfill", "EmbeddingService · backfill", "every 5m", "—", "backfillEmbeddings"),
            new CatalogEntry(
                    "event-clusters",
                    "EventClusteringService · cluster refresh",
                    "every 10m",
                    "—",
                    "updateEventClusters"),
            new CatalogEntry("contract-graph", "ContractGraphPoller", "every 60m", "—", "contractGraphPoller"),
            new CatalogEntry(
                    "discord-init-channel-listener",
                    "DiscordChannelInitListener",
                    "(reactive)",
                    "EventChannelReady",
                    null),
            new CatalogEntry(
                    "discord-init-roles-listener", "DiscordRolesInitListener", "(reactive)", "EventRolesReady", null),
            new CatalogEntry("event-classify-listener", "EventClassifyListener", "(reactive)", "EventClassified", null),
            new CatalogEntry(
                    "event-init-complete-listener", "EventInitCompleteListener", "(reactive)", "EventPlanned", null),
            new CatalogEntry(
                    "pre-event-notify-listener",
                    "PreEventNotificationListener",
                    "(reactive)",
                    "EventPreNotified",
                    null),
            new CatalogEntry("event-complete-listener", "EventCompleteListener", "(reactive)", "EventCompleted", null),
            new CatalogEntry("event-archive-listener", "EventArchiveListener", "(reactive)", "EventArchived", null),
            new CatalogEntry("event-cancel-listener", "EventCancelListener", "(reactive)", "EventCancelled", null),
            new CatalogEntry("event-delete-listener", "EventDeleteListener", "(reactive)", "EventDeleted", null),
            new CatalogEntry("immich-album-prep-listener", "ImmichAlbumPrepListener", "(reactive)", "—", null),
            new CatalogEntry("immich-album-post-listener", "ImmichAlbumPostListener", "(reactive)", "—", null),
            new CatalogEntry("tfnsw-event-created-listener", "TfnswEventCreatedListener", "(reactive)", "—", null),
            new CatalogEntry("tfnsw-follow-up", "TfnswFollowUpPoller", "0 0 6 * * *", "—", "tfnswFollowUpPoller"));

    private final JdbcTemplate jdbc;

    public AdminJobCatalog(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public List<AdminJobDto> snapshot() {
        Map<String, ShedLockRow> shedlockRows = readShedLockRows();
        return CATALOG.stream()
                .map(entry -> {
                    Instant lastRun = null;
                    Instant nextRun = null;
                    String status = "ok";
                    if (entry.shedLockName() != null) {
                        ShedLockRow row = shedlockRows.get(entry.shedLockName());
                        if (row != null) {
                            lastRun = row.lockedAt();
                        }
                    }
                    if (looksLikeCron(entry.cron())) {
                        nextRun = nextRunForCron(entry.cron(), lastRun);
                    }
                    return new AdminJobDto(
                            entry.id(), entry.label(), entry.cron(), entry.emits(), lastRun, nextRun, null, status);
                })
                .toList();
    }

    private record ShedLockRow(String name, Instant lockedAt, Instant lockUntil) {}

    private Map<String, ShedLockRow> readShedLockRows() {
        try {
            List<ShedLockRow> rows = jdbc.query(
                    "SELECT name, locked_at, lock_until FROM shedlock",
                    (rs, i) -> new ShedLockRow(
                            rs.getString("name"),
                            Optional.ofNullable(rs.getTimestamp("locked_at"))
                                    .map(java.sql.Timestamp::toInstant)
                                    .orElse(null),
                            Optional.ofNullable(rs.getTimestamp("lock_until"))
                                    .map(java.sql.Timestamp::toInstant)
                                    .orElse(null)));
            return rows.stream().collect(java.util.stream.Collectors.toMap(ShedLockRow::name, r -> r, (a, b) -> b));
        } catch (Exception e) {
            log.debug("shedlock table not yet readable: {}", e.toString());
            return Map.of();
        }
    }

    private static boolean looksLikeCron(String cron) {
        return cron != null && cron.contains("*");
    }

    private static Instant nextRunForCron(String cron, Instant from) {
        try {
            CronExpression expr = CronExpression.parse(cron);
            java.time.ZonedDateTime base =
                    (from != null ? from : Instant.now()).atZone(java.time.ZoneId.systemDefault());
            java.time.ZonedDateTime next = expr.next(base);
            return next != null ? next.toInstant() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
