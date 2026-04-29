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
