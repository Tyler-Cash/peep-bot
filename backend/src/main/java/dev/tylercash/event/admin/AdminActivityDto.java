package dev.tylercash.event.admin;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in the admin activity firehose. Synthesised from a ListenerInvocation row joined
 * back to its Event for a human-readable label and guild attribution.
 */
public record AdminActivityDto(
        Instant ts,
        String kind,
        String text,
        String detail,
        UUID eventId,
        String guildId,
        String lifecycleEventType,
        String listenerName,
        int attempts) {}
