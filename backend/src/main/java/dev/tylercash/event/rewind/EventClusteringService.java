package dev.tylercash.event.rewind;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.EventState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;
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
    private final EventRepository eventRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Scheduled(fixedDelayString = "PT10M")
    @SchedulerLock(name = "updateEventClusters", lockAtLeastFor = "PT2M", lockAtMostFor = "PT15M")
    @Transactional
    public void updateClusters() {
        if (!config.isEnabled()) return;

        performFixedClassification();
    }

    private void performFixedClassification() {
        var events = eventRepository.findAllByState(EventState.CLASSIFY);
        if (events.isEmpty()) return;

        int updated = 0;
        for (var event : events) {
            try {
                String category = embeddingService.classify(event);
                saveCategory(event.getId(), category);
                updated++;
            } catch (Exception e) {
                log.warn("Failed to classify event '{}': {}", event.getName(), e.getMessage());
            }
        }
        log.info("Updated {} event categories using fixed classification", updated);
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
