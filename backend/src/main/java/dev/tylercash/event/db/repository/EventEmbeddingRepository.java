package dev.tylercash.event.db.repository;

import dev.tylercash.event.rewind.model.EventEmbedding;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EventEmbeddingRepository extends JpaRepository<EventEmbedding, UUID> {

    @Query(
            value =
                    """
            SELECT e.id FROM event e
            LEFT JOIN event_embedding ee ON e.id = ee.event_id
            WHERE ee.event_id IS NULL
            LIMIT :batchSize
            """,
            nativeQuery = true)
    List<UUID> findEventIdsWithoutEmbedding(@Param("batchSize") int batchSize);
}
