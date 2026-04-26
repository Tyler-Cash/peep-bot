package dev.tylercash.event.rewind;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventClusteringService {

    private final EmbeddingService embeddingService;
    private final RewindConfiguration config;

    @PersistenceContext
    private EntityManager entityManager;

    @Scheduled(fixedDelayString = "PT10M")
    @SchedulerLock(name = "updateEventClusters", lockAtLeastFor = "PT2M", lockAtMostFor = "PT15M")
    @Transactional
    public void updateClusters() {
        if (!config.isEnabled()) return;

        performFixedClassification();
    }

    @SuppressWarnings("unchecked")
    private void performFixedClassification() {
        List<Object[]> rows = entityManager
                .createNativeQuery("SELECT e.id, e.name FROM event e")
                .getResultList();

        for (Object[] row : rows) {
            UUID id = (UUID) row[0];
            String name = (String) row[1];
            try {
                String category = embeddingService.classify(name);
                saveCategory(id, category);
            } catch (Exception e) {
                log.warn("Failed to classify event '{}': {}", name, e.getMessage());
            }
        }
        log.info("Updated {} event categories using fixed classification", rows.size());
    }

    private void saveCategory(UUID eventId, String category) {
        entityManager
                .createNativeQuery(
                        """
            INSERT INTO event_category (event_id, category_label, category_centroid_event_id, assigned_at)
            VALUES (CAST(:eventId AS UUID), :label, CAST(:centroidId AS UUID), NOW())
            ON CONFLICT (event_id) DO UPDATE SET
                category_label = EXCLUDED.category_label,
                assigned_at = EXCLUDED.assigned_at
            """)
                .setParameter("eventId", eventId.toString())
                .setParameter("label", category)
                .setParameter("centroidId", eventId.toString())
                .executeUpdate();
    }
}
