package dev.tylercash.event.lifecycle;

import java.time.Instant;
import java.util.List;
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
}
