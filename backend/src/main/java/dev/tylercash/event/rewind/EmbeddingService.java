package dev.tylercash.event.rewind;

import dev.tylercash.event.db.repository.EventEmbeddingRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.rewind.model.EventEmbedding;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final TextNormalisationService normalisationService;
    private final RewindConfiguration config;
    private final EventRepository eventRepository;
    private final EventEmbeddingRepository embeddingRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public EmbeddingService(
            @Autowired(required = false) EmbeddingModel embeddingModel,
            TextNormalisationService normalisationService,
            RewindConfiguration config,
            EventRepository eventRepository,
            EventEmbeddingRepository embeddingRepository) {
        this.normalisationService = normalisationService;
        this.config = config;
        this.eventRepository = eventRepository;
        this.embeddingRepository = embeddingRepository;
        if (embeddingModel != null) {
            this.embeddingModel = embeddingModel;
            log.info("EmbeddingService initialized — semantic clustering enabled");
        } else {
            this.embeddingModel = null;
            log.info("No EmbeddingModel bean available — falling back to name-based clustering");
        }
    }

    public boolean isEmbeddingsAvailable() {
        return embeddingModel != null && config.isEnabled();
    }

    public String classify(Event event) {
        return normalisationService.classify(event);
    }

    String buildEmbeddingText(Event event) {
        return event.getName();
    }

    @Transactional
    public void embedEvent(Event event) {
        if (!isEmbeddingsAvailable()) return;
        try {
            String nameText = buildEmbeddingText(event);
            float[] raw = embeddingModel.embed(nameText);
            embeddingRepository.save(
                    new EventEmbedding(event.getId(), nameText, toVectorString(raw), OffsetDateTime.now()));

            String category = normalisationService.classify(event);
            saveCategory(event.getId(), category);
        } catch (Exception e) {
            log.error("Failed to embed event: {}", e.getMessage());
        }
    }

    @Transactional
    public void classifyEvent(Event event) {
        if (isEmbeddingsAvailable()) {
            embedEvent(event);
        } else if (normalisationService.isAvailable()) {
            try {
                String category = normalisationService.classify(event);
                saveCategory(event.getId(), category);
            } catch (Exception e) {
                log.warn("Classification failed for event '{}': {}", event.getName(), e.getMessage());
            }
        }
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

    @Scheduled(fixedDelayString = "PT5M")
    @SchedulerLock(name = "backfillEmbeddings", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    @Transactional
    public void backfillMissingEmbeddings() {
        if (!isEmbeddingsAvailable()) return;

        List<UUID> missingIds = embeddingRepository.findEventIdsWithoutEmbedding(config.getBackfillBatchSize());
        if (missingIds.isEmpty()) return;

        log.info("Backfilling embeddings for {} events", missingIds.size());
        for (UUID id : missingIds) {
            eventRepository.findById(id).ifPresent(this::embedEvent);
        }
    }

    private String toVectorString(float[] embedding) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
