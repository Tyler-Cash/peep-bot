package dev.tylercash.event.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Event with its full lifecycle history, used by the admin Events screen. */
public record AdminEventDto(
        UUID id,
        String guildId,
        String name,
        String category,
        String state,
        String when,
        String location,
        String creator,
        int going,
        int maybe,
        int declined,
        Instant createdAt,
        List<HistoryEntry> history) {

    public record HistoryEntry(
            String stage,
            String lifecycleEventType,
            String listenerName,
            Instant ts,
            boolean ok,
            int attempts,
            String detail) {}
}
