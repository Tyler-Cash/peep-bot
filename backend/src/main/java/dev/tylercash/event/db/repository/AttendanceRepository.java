package dev.tylercash.event.db.repository;

import dev.tylercash.event.event.model.AttendanceRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AttendanceRepository extends JpaRepository<AttendanceRecord, Long> {

    @Query(
            value =
                    """
            SELECT DISTINCT ON (COALESCE(snowflake, name)) *
            FROM attendance
            WHERE event_id = :eventId
            ORDER BY COALESCE(snowflake, name), recorded_at DESC
            """,
            nativeQuery = true)
    List<AttendanceRecord> findLatestPerAttendee(@Param("eventId") UUID eventId);

    @Query(
            value =
                    """
            SELECT DISTINCT snowflake
            FROM attendance
            WHERE snowflake IS NOT NULL
            """,
            nativeQuery = true)
    List<String> findAllDistinctSnowflakes();
}
