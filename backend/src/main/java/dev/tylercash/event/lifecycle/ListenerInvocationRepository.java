package dev.tylercash.event.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListenerInvocationRepository extends JpaRepository<ListenerInvocation, ListenerInvocationId> {

    @Query(
            """
            SELECT i FROM ListenerInvocation i
            WHERE i.status IN ('PENDING','FAILED')
              AND (i.nextRetryAt IS NULL OR i.nextRetryAt <= :now)
            ORDER BY i.nextRetryAt ASC NULLS FIRST
            """)
    List<ListenerInvocation> findDueForRetry(@Param("now") Instant now, Pageable page);

    /** All invocations for one event, oldest first — used to reconstruct lifecycle history. */
    List<ListenerInvocation> findByEventIdOrderByCreatedAtAsc(UUID eventId);

    /** Recent invocations across all events for the activity firehose. */
    List<ListenerInvocation> findTop50ByOrderByUpdatedAtDesc();

    /** Invocations for events in a specific guild, paged. */
    @Query(
            """
            SELECT i FROM ListenerInvocation i, Event e
            WHERE i.eventId = e.id
              AND e.serverId = :guildId
            ORDER BY i.updatedAt DESC
            """)
    List<ListenerInvocation> findRecentForGuild(@Param("guildId") long guildId, Pageable page);

    /** Count of FAILED invocations for events in a specific guild — used by Guilds table. */
    @Query(
            """
            SELECT COUNT(i) FROM ListenerInvocation i, Event e
            WHERE i.eventId = e.id
              AND e.serverId = :guildId
              AND i.status = dev.tylercash.event.lifecycle.ListenerInvocationStatus.FAILED
              AND i.updatedAt >= :since
            """)
    long countFailedForGuildSince(@Param("guildId") long guildId, @Param("since") Instant since);

    long countByStatus(ListenerInvocationStatus status);

    @Query(
            """
            SELECT COUNT(i) FROM ListenerInvocation i
            WHERE i.status = dev.tylercash.event.lifecycle.ListenerInvocationStatus.FAILED
              AND i.attempts > :threshold
            """)
    long countStuck(@Param("threshold") int threshold);
}
