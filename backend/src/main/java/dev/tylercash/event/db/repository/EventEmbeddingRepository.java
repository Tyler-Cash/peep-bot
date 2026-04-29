package dev.tylercash.event.db.repository;

import dev.tylercash.event.rewind.model.EventEmbedding;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface EventEmbeddingRepository extends JpaRepository<EventEmbedding, UUID> {

    @Query(
            """
            SELECT e.id FROM Event e
            WHERE NOT EXISTS (SELECT 1 FROM EventEmbedding ee WHERE ee.eventId = e.id)
            """)
    List<UUID> findEventIdsWithoutEmbedding(Pageable pageable);
}
