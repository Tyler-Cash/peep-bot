package dev.tylercash.event.db.repository;

import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {
    Page<Event> findAll(Pageable pageable);

    Page<Event> findAllByStateEquals(Pageable pageable, EventState state);

    Page<Event> findAllByStateNotIn(Pageable pageable, List<EventState> states);

    Page<Event> findAllByStateNotInAndServerId(Pageable pageable, List<EventState> states, long serverId);

    List<Event> findAllByServerId(long serverId);

    int countByServerId(long serverId);

    int countByServerIdAndStateNotIn(long serverId, List<EventState> states);

    Page<Event> findAllByStateIn(Pageable pageable, List<EventState> states);

    Optional<Event> findById(UUID id);

    Event findByMessageId(Long id);

    Event findByChannelId(Long id);

    List<Event> findByChannelIdIn(List<Long> channelIds);

    List<Event> findByPrivateChannelIdIn(List<Long> channelIds);

    Page<Event> findByState(Pageable pageable, EventState state);

    List<Event> findAllByState(EventState state);

    @Query("SELECT DISTINCT e.creator FROM Event e WHERE e.creator IS NOT NULL AND e.creator <> ''")
    List<String> findAllDistinctCreatorSnowflakes();

    @Query(
            value =
                    """
                    SELECT e FROM Event e
                    WHERE e.state = dev.tylercash.event.event.model.EventState.PLANNED
                    AND NOT EXISTS (
                        SELECT 1 FROM EventCategory ec WHERE ec.eventId = e.id
                    )
                    """,
            countQuery =
                    """
                    SELECT COUNT(e) FROM Event e
                    WHERE e.state = dev.tylercash.event.event.model.EventState.PLANNED
                    AND NOT EXISTS (
                        SELECT 1 FROM EventCategory ec WHERE ec.eventId = e.id
                    )
                    """)
    Page<Event> findPlannedEventsWithoutCategory(Pageable pageable);

    @Query(
            """
            SELECT e FROM Event e
            WHERE e.serverId = :serverId
            AND e.immichAlbumId IS NOT NULL
            AND EXISTS (
                SELECT 1 FROM AttendanceRecord a
                WHERE a.eventId = e.id
                AND a.snowflake = :snowflake
                AND a.status = :status
            )
            ORDER BY e.dateTime DESC
            """)
    List<Event> findGalleryEventsForUser(long serverId, String snowflake, AttendanceStatus status);

    default List<Event> findGalleryEventsForUser(long serverId, String snowflake) {
        return findGalleryEventsForUser(serverId, snowflake, AttendanceStatus.ACCEPTED);
    }

    @Query(
            """
            SELECT e FROM Event e
            WHERE e.immichAlbumId = :albumId
            AND EXISTS (
                SELECT 1 FROM AttendanceRecord a
                WHERE a.eventId = e.id
                AND a.snowflake = :snowflake
                AND a.status = :status
            )
            """)
    List<Event> findGalleryEventByAlbumIdForUser(
            String albumId, String snowflake, AttendanceStatus status, Pageable pageable);

    default Optional<Event> findGalleryEventByAlbumIdForUser(String albumId, String snowflake) {
        return findGalleryEventByAlbumIdForUser(albumId, snowflake, AttendanceStatus.ACCEPTED, Pageable.ofSize(1))
                .stream()
                .findFirst();
    }

    // ---------------------------------------------------------------------------------------
    // Rewind aggregations. All queries optionally filter by year via
    // (:year IS NULL OR extract(year from e.dateTime) = :year), and always exclude
    // CANCELLED events. Grouped queries return Object[] tuples for the caller to map.
    // ---------------------------------------------------------------------------------------

    @Query(
            """
            SELECT DISTINCT extract(year from e.dateTime) FROM Event e
            WHERE e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            ORDER BY 1 DESC
            """)
    List<Integer> findDistinctYearsForGuild(long guildId);

    @Query(
            """
            SELECT count(e) FROM Event e
            WHERE e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            """)
    long countTotalEventsGuildWide(long guildId, Integer year);

    @Query(
            """
            SELECT count(distinct a.eventId) FROM AttendanceRecord a, Event e
            WHERE a.eventId = e.id
            AND a.snowflake = :snowflake
            AND a.status = dev.tylercash.event.event.model.AttendanceStatus.ACCEPTED
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            """)
    long countTotalEventsPersonal(String snowflake, long guildId, Integer year);

    @Query(
            """
            SELECT count(distinct a.snowflake) FROM AttendanceRecord a, Event e
            WHERE a.eventId = e.id
            AND a.status = dev.tylercash.event.event.model.AttendanceStatus.ACCEPTED
            AND a.snowflake IS NOT NULL
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            """)
    long countUniqueAttendeesGuildWide(long guildId, Integer year);

    @Query(
            """
            SELECT count(distinct a.snowflake) FROM AttendanceRecord a, AttendanceRecord a2, Event e
            WHERE a.eventId = e.id
            AND a2.eventId = a.eventId
            AND a.status = dev.tylercash.event.event.model.AttendanceStatus.ACCEPTED
            AND a.snowflake IS NOT NULL
            AND a2.snowflake = :snowflake
            AND a2.status = dev.tylercash.event.event.model.AttendanceStatus.ACCEPTED
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            """)
    long countUniqueAttendeesPersonal(String snowflake, long guildId, Integer year);

    @Query(
            """
            SELECT count(a) FROM AttendanceRecord a, Event e
            WHERE a.eventId = e.id
            AND a.status <> dev.tylercash.event.event.model.AttendanceStatus.REMOVED
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            """)
    long countTotalRsvpsGuildWide(long guildId, Integer year);

    @Query(
            """
            SELECT count(a) FROM AttendanceRecord a, Event e
            WHERE a.eventId = e.id
            AND a.snowflake = :snowflake
            AND a.status <> dev.tylercash.event.event.model.AttendanceStatus.REMOVED
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            """)
    long countTotalRsvpsPersonal(String snowflake, long guildId, Integer year);

    @Query(
            """
            SELECT a.snowflake, count(distinct a.eventId) FROM AttendanceRecord a, Event e
            WHERE a.eventId = e.id
            AND a.status = dev.tylercash.event.event.model.AttendanceStatus.ACCEPTED
            AND a.snowflake IS NOT NULL
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            GROUP BY a.snowflake
            ORDER BY count(distinct a.eventId) DESC
            """)
    List<Object[]> findTopAttendees(long guildId, Integer year, Pageable pageable);

    @Query(
            """
            SELECT e.creator, count(e) FROM Event e
            WHERE e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            GROUP BY e.creator
            ORDER BY count(e) DESC
            """)
    List<Object[]> findTopOrganizers(long guildId, Integer year, Pageable pageable);

    @Query(
            """
            SELECT count(a) FROM AttendanceRecord a, Event e
            WHERE a.eventId = e.id
            AND a.snowflake IS NULL
            AND a.status = dev.tylercash.event.event.model.AttendanceStatus.ACCEPTED
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            """)
    long countPlusOnesGuildWide(long guildId, Integer year);

    @Query(
            """
            SELECT count(a) FROM AttendanceRecord a, Event e
            WHERE a.eventId = e.id
            AND a.snowflake IS NULL
            AND a.status = dev.tylercash.event.event.model.AttendanceStatus.ACCEPTED
            AND a.ownerSnowflake = :snowflake
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            """)
    long countPlusOnesPersonal(String snowflake, long guildId, Integer year);

    @Query(
            """
            SELECT ec.categoryLabel, count(distinct e.id), count(distinct a.snowflake)
            FROM EventCategory ec
            JOIN Event e ON e.id = ec.eventId
            LEFT JOIN AttendanceRecord a ON a.eventId = e.id
                AND a.status = dev.tylercash.event.event.model.AttendanceStatus.ACCEPTED
                AND a.snowflake IS NOT NULL
            WHERE e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            GROUP BY ec.categoryLabel
            ORDER BY count(distinct e.id) DESC
            """)
    List<Object[]> findTopCategoriesGuildWide(long guildId, Integer year, Pageable pageable);

    @Query(
            """
            SELECT ec.categoryLabel, count(distinct e.id), count(distinct a.snowflake)
            FROM EventCategory ec
            JOIN Event e ON e.id = ec.eventId
            JOIN AttendanceRecord a ON a.eventId = e.id
                AND a.status = dev.tylercash.event.event.model.AttendanceStatus.ACCEPTED
                AND a.snowflake IS NOT NULL
            WHERE a.snowflake = :snowflake
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            GROUP BY ec.categoryLabel
            ORDER BY count(distinct e.id) DESC
            """)
    List<Object[]> findTopCategoriesPersonal(String snowflake, long guildId, Integer year, Pageable pageable);

    @Query(
            """
            SELECT format(e.dateTime as 'yyyy-MM'), count(e)
            FROM Event e
            WHERE e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            GROUP BY format(e.dateTime as 'yyyy-MM')
            ORDER BY format(e.dateTime as 'yyyy-MM')
            """)
    List<Object[]> findEventsByMonthGuildWide(long guildId, Integer year);

    @Query(
            """
            SELECT format(e.dateTime as 'yyyy-MM'), count(distinct e.id)
            FROM Event e JOIN AttendanceRecord a ON a.eventId = e.id
            WHERE a.snowflake = :snowflake
            AND a.status = dev.tylercash.event.event.model.AttendanceStatus.ACCEPTED
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            GROUP BY format(e.dateTime as 'yyyy-MM')
            ORDER BY format(e.dateTime as 'yyyy-MM')
            """)
    List<Object[]> findEventsByMonthPersonal(String snowflake, long guildId, Integer year);

    @Query(
            """
            SELECT extract(day of week from e.dateTime), count(e)
            FROM Event e
            WHERE e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            GROUP BY extract(day of week from e.dateTime)
            ORDER BY extract(day of week from e.dateTime)
            """)
    List<Object[]> findEventsByDayOfWeekGuildWide(long guildId, Integer year);

    @Query(
            """
            SELECT extract(day of week from e.dateTime), count(distinct e.id)
            FROM Event e JOIN AttendanceRecord a ON a.eventId = e.id
            WHERE a.snowflake = :snowflake
            AND a.status = dev.tylercash.event.event.model.AttendanceStatus.ACCEPTED
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            GROUP BY extract(day of week from e.dateTime)
            ORDER BY extract(day of week from e.dateTime)
            """)
    List<Object[]> findEventsByDayOfWeekPersonal(String snowflake, long guildId, Integer year);

    @Query(
            """
            SELECT new dev.tylercash.event.rewind.model.EventSummaryDto(e.id, e.name, e.dateTime)
            FROM Event e
            WHERE e.dateTime IS NOT NULL
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            ORDER BY e.dateTime ASC
            """)
    List<dev.tylercash.event.rewind.model.EventSummaryDto> findFirstEventGuildWide(
            long guildId, Integer year, Pageable pageable);

    @Query("SELECT e FROM Event e WHERE e.dateTime BETWEEN :from AND :to AND e.state = :state")
    List<Event> findInDateWindow(
            @Param("from") ZonedDateTime from, @Param("to") ZonedDateTime to, @Param("state") EventState state);

    @Query(
            """
            SELECT new dev.tylercash.event.rewind.model.EventSummaryDto(e.id, e.name, e.dateTime)
            FROM Event e JOIN AttendanceRecord a ON a.eventId = e.id
            WHERE a.snowflake = :snowflake
            AND a.status = dev.tylercash.event.event.model.AttendanceStatus.ACCEPTED
            AND e.dateTime IS NOT NULL
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            ORDER BY e.dateTime ASC
            """)
    List<dev.tylercash.event.rewind.model.EventSummaryDto> findFirstEventPersonal(
            String snowflake, long guildId, Integer year, Pageable pageable);

    @Query(
            """
            SELECT new dev.tylercash.event.rewind.model.EventSummaryDto(e.id, e.name, e.dateTime)
            FROM Event e
            WHERE e.dateTime IS NOT NULL
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            ORDER BY e.dateTime DESC
            """)
    List<dev.tylercash.event.rewind.model.EventSummaryDto> findLastEventGuildWide(
            long guildId, Integer year, Pageable pageable);

    @Query(
            """
            SELECT new dev.tylercash.event.rewind.model.EventSummaryDto(e.id, e.name, e.dateTime)
            FROM Event e JOIN AttendanceRecord a ON a.eventId = e.id
            WHERE a.snowflake = :snowflake
            AND a.status = dev.tylercash.event.event.model.AttendanceStatus.ACCEPTED
            AND e.dateTime IS NOT NULL
            AND e.serverId = :guildId
            AND e.state <> dev.tylercash.event.event.model.EventState.CANCELLED
            AND (:year IS NULL OR extract(year from e.dateTime) = :year)
            ORDER BY e.dateTime DESC
            """)
    List<dev.tylercash.event.rewind.model.EventSummaryDto> findLastEventPersonal(
            String snowflake, long guildId, Integer year, Pageable pageable);
}
