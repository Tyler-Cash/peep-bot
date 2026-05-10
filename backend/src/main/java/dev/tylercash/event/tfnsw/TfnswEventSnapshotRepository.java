package dev.tylercash.event.tfnsw;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TfnswEventSnapshotRepository extends JpaRepository<TfnswEventSnapshot, UUID> {
    /**
     * Returns the IDs of events whose date falls in [from, to) AND whose guild has tfnsw_enabled=true.
     * Used by the week-before recheck poller.
     */
    @Query(
            """
        SELECT e.id FROM Event e, Guild g
        WHERE g.guildId = e.serverId
          AND g.tfnswEnabled = true
          AND e.dateTime >= :from AND e.dateTime < :to
        """)
    List<UUID> findEventIdsForWeekBeforeCheck(@Param("from") ZonedDateTime from, @Param("to") ZonedDateTime to);
}
