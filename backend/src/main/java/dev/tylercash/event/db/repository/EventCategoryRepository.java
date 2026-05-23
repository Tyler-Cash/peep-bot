package dev.tylercash.event.db.repository;

import dev.tylercash.event.event.EventCategory;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EventCategoryRepository extends JpaRepository<EventCategory, UUID> {
    List<EventCategory> findByEventIdIn(Collection<UUID> eventIds);

    /** Stream all event IDs whose category was assigned before the cutoff. Caller iterates lazily. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT ec.eventId FROM EventCategory ec WHERE ec.assignedAt < :cutoff")
    java.util.stream.Stream<java.util.UUID> findEventIdsAssignedBefore(
            @org.springframework.data.repository.query.Param("cutoff") java.time.OffsetDateTime cutoff);

    /**
     * Upsert by event id: insert with centroid=eventId on first save, update only label and
     * assignedAt on subsequent calls (preserving any existing centroid_event_id). Mirrors the
     * previous {@code INSERT ... ON CONFLICT DO UPDATE} behaviour.
     */
    default EventCategory upsertCategory(UUID eventId, String label) {
        EventCategory ec = findById(eventId).orElseGet(() -> {
            EventCategory created = new EventCategory();
            created.setEventId(eventId);
            created.setCategoryCentroidEventId(eventId);
            return created;
        });
        ec.setCategoryLabel(label);
        ec.setAssignedAt(OffsetDateTime.now());
        return save(ec);
    }
}
