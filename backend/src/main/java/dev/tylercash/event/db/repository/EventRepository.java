package dev.tylercash.event.db.repository;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {
    Page<Event> findAll(Pageable pageable);

    Page<Event> findAllByStateEquals(Pageable pageable, EventState state);

    Page<Event> findAllByStateNotIn(Pageable pageable, List<EventState> states);

    Page<Event> findAllByStateNotInAndServerId(Pageable pageable, List<EventState> states, long serverId);

    Page<Event> findAllByStateIn(Pageable pageable, List<EventState> states);

    Optional<Event> findById(UUID id);

    Event findByMessageId(Long id);

    Event findByChannelId(Long id);

    List<Event> findByChannelIdIn(List<Long> channelIds);

    List<Event> findByPrivateChannelIdIn(List<Long> channelIds);

    Page<Event> findByState(Pageable pageable, EventState state);

    List<Event> findAllByState(EventState state);

    @Query(value = "SELECT DISTINCT creator FROM event WHERE creator IS NOT NULL AND creator != ''", nativeQuery = true)
    List<String> findAllDistinctCreatorSnowflakes();

    @Query(
            value = "SELECT e.* FROM event e "
                    + "WHERE e.state = 'PLANNED' "
                    + "AND NOT EXISTS ("
                    + "    SELECT 1 FROM event_category ec WHERE ec.event_id = e.id"
                    + ")",
            nativeQuery = true)
    Page<Event> findPlannedEventsWithoutCategory(Pageable pageable);

    @Query(
            value = "SELECT COALESCE(ec.category_label, 'unknown') FROM event e "
                    + "LEFT JOIN event_category ec ON ec.event_id = e.id "
                    + "WHERE e.id = :eventId",
            nativeQuery = true)
    String findCategoryByEventId(UUID eventId);

    @Query(
            value = "SELECT CAST(e.id AS text), COALESCE(ec.category_label, 'unknown') FROM event e "
                    + "LEFT JOIN event_category ec ON ec.event_id = e.id "
                    + "WHERE e.id IN :eventIds",
            nativeQuery = true)
    List<Object[]> findCategoriesByEventIds(List<UUID> eventIds);

    @Query(
            value = "SELECT * FROM event e "
                    + "WHERE e.server_id = :serverId "
                    + "AND e.immich_album_id IS NOT NULL "
                    + "AND EXISTS ("
                    + "  SELECT 1 FROM attendance a "
                    + "  WHERE a.event_id = e.id AND a.snowflake = :snowflake AND a.status = 'ACCEPTED'"
                    + ") "
                    + "ORDER BY e.date_time DESC",
            nativeQuery = true)
    List<Event> findGalleryEventsForUser(long serverId, String snowflake);

    @Query(
            value = "SELECT * FROM event e "
                    + "WHERE e.immich_album_id = :albumId "
                    + "AND EXISTS ("
                    + "  SELECT 1 FROM attendance a "
                    + "  WHERE a.event_id = e.id AND a.snowflake = :snowflake AND a.status = 'ACCEPTED'"
                    + ") "
                    + "LIMIT 1",
            nativeQuery = true)
    Optional<Event> findGalleryEventByAlbumIdForUser(String albumId, String snowflake);
}
