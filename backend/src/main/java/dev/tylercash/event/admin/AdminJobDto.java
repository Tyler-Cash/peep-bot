package dev.tylercash.event.admin;

import java.time.Instant;

/**
 * Scheduler/listener job metadata for the admin Jobs screen. cron may be a cron expression,
 * a fixedDelay description ("every 60s"), or "(reactive)" for event-driven listeners.
 * lastRun / nextRun come from the shedlock table when available; null otherwise.
 */
public record AdminJobDto(
        String id,
        String label,
        String cron,
        String emits,
        Instant lastRun,
        Instant nextRun,
        String lastDuration,
        String lastStatus) {}
