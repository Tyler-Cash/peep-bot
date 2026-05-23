package dev.tylercash.event.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface ListenerInvocationRepository extends JpaRepository<ListenerInvocation, ListenerInvocationId> {

    /**
     * Atomically claim a row for invocation: transition PENDING/FAILED → IN_PROGRESS. Returns the
     * number of rows updated (1 = caller owns the invocation; 0 = another worker beat us to it, or
     * the row is already SUCCESS/IN_PROGRESS). Runs in its own transaction so concurrent callers
     * see each other's commits — without this the dispatcher and retry poller can both invoke the
     * same row and double-fire downstream publishes (duplicate-key on the next outbox insert).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(
            """
            UPDATE ListenerInvocation i
            SET i.status = dev.tylercash.event.lifecycle.ListenerInvocationStatus.IN_PROGRESS,
                i.lastAttemptAt = :now
            WHERE i.eventId = :eventId
              AND i.lifecycleEventType = :type
              AND i.listenerName = :listener
              AND (i.status = dev.tylercash.event.lifecycle.ListenerInvocationStatus.PENDING
                   OR i.status = dev.tylercash.event.lifecycle.ListenerInvocationStatus.FAILED)
            """)
    int claim(
            @Param("eventId") UUID eventId,
            @Param("type") String type,
            @Param("listener") String listener,
            @Param("now") Instant now);

    /**
     * Atomically transition a claimed row to SUCCESS. Runs in REQUIRES_NEW for the same reason
     * {@link #claim} does: markSuccess is invoked from a {@code CompletableFuture.whenComplete}
     * callback which, when the surrounding {@code @TransactionalEventListener(AFTER_COMMIT)} runs
     * synchronously and the listener executor completes the future inline (notably the final
     * EventCompleted/Immich Album Post stage in tests), executes on a thread still inside Spring's
     * transaction-synchronization processing. A plain {@code JpaRepository.save(detachedRow)} from
     * that context relied on a merge through whatever persistence-context state was still bound
     * to the thread and silently failed to land the UPDATE — leaving the row stuck IN_PROGRESS with
     * {@code lastError=null}. A direct JPQL UPDATE in its own transaction avoids the entire detached
     * merge path.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(
            """
            UPDATE ListenerInvocation i
            SET i.status = dev.tylercash.event.lifecycle.ListenerInvocationStatus.SUCCESS,
                i.lastAttemptAt = :now,
                i.lastError = null,
                i.updatedAt = :now
            WHERE i.eventId = :eventId
              AND i.lifecycleEventType = :type
              AND i.listenerName = :listener
            """)
    int markSuccess(
            @Param("eventId") UUID eventId,
            @Param("type") String type,
            @Param("listener") String listener,
            @Param("now") Instant now);

    /**
     * Atomically transition a claimed row to FAILED, incrementing {@code attempts} and recording
     * the next retry timestamp. Same REQUIRES_NEW rationale as {@link #markSuccess}. The attempts
     * column is incremented in SQL so concurrent observers always see a monotonic value.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(
            """
            UPDATE ListenerInvocation i
            SET i.status = dev.tylercash.event.lifecycle.ListenerInvocationStatus.FAILED,
                i.attempts = i.attempts + 1,
                i.lastAttemptAt = :now,
                i.nextRetryAt = :nextRetryAt,
                i.lastError = :error,
                i.updatedAt = :now
            WHERE i.eventId = :eventId
              AND i.lifecycleEventType = :type
              AND i.listenerName = :listener
            """)
    int markFailed(
            @Param("eventId") UUID eventId,
            @Param("type") String type,
            @Param("listener") String listener,
            @Param("now") Instant now,
            @Param("nextRetryAt") Instant nextRetryAt,
            @Param("error") String error);

    /**
     * Reset IN_PROGRESS rows whose claim is older than {@code cutoff} back to PENDING so the retry
     * poller can re-attempt them. Covers the case where the worker that claimed the row died
     * before markSuccess/markFailed ran (JVM crash, kill -9). Returns the number of rows reset.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(
            """
            UPDATE ListenerInvocation i
            SET i.status = dev.tylercash.event.lifecycle.ListenerInvocationStatus.PENDING
            WHERE i.status = dev.tylercash.event.lifecycle.ListenerInvocationStatus.IN_PROGRESS
              AND i.lastAttemptAt < :cutoff
            """)
    int reclaimStuckInProgress(@Param("cutoff") Instant cutoff);

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
