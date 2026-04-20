package dev.tylercash.event.rewind;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
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
    private final RewindConfiguration config;
    private final EventRepository eventRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public EmbeddingService(
            @Autowired(required = false) EmbeddingModel embeddingModel,
            RewindConfiguration config,
            EventRepository eventRepository) {
        this.config = config;
        this.eventRepository = eventRepository;
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

    String buildEmbeddingText(Event event) {
        var sb = new StringBuilder(event.getName());
        sb.append(" | ").append(event.getDateTime().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        if (event.getDescription() != null && !event.getDescription().isBlank()) {
            sb.append(" | ").append(event.getDescription().trim());
        }
        if (event.getCapacity() != null && event.getCapacity() > 0) {
            sb.append(" | capacity: ").append(event.getCapacity());
        }
        return sb.toString();
    }

    @Transactional
    public void embedEvent(UUID eventId, String nameText) {
        if (!isEmbeddingsAvailable()) return;
        try {
            float[] embedding = embeddingModel.embed(nameText);
            String vectorStr = toVectorString(embedding);
            entityManager
                    .createNativeQuery("INSERT INTO event_embedding (event_id, name_text, embedding, computed_at) "
                            + "VALUES (CAST(:eventId AS UUID), :nameText, :embedding::vector, NOW()) "
                            + "ON CONFLICT (event_id) DO UPDATE SET name_text = :nameText, "
                            + "embedding = :embedding::vector, computed_at = NOW()")
                    .setParameter("eventId", eventId.toString())
                    .setParameter("nameText", nameText)
                    .setParameter("embedding", vectorStr)
                    .executeUpdate();
        } catch (Exception e) {
            log.error("Failed to embed event {}: {}", eventId, e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "PT5M")
    @SchedulerLock(name = "backfillEmbeddings", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    @Transactional
    public void backfillMissingEmbeddings() {
        if (!isEmbeddingsAvailable()) return;

        @SuppressWarnings("unchecked")
        List<UUID> missingIds = entityManager
                .createNativeQuery("SELECT e.id FROM event e "
                        + "LEFT JOIN event_embedding ee ON e.id = ee.event_id "
                        + "WHERE ee.event_id IS NULL "
                        + "LIMIT :batchSize")
                .setParameter("batchSize", config.getBackfillBatchSize())
                .getResultList();

        if (missingIds.isEmpty()) return;

        log.info("Backfilling embeddings for {} events", missingIds.size());
        for (UUID id : missingIds) {
            eventRepository.findById(id).ifPresent(event -> {
                String text = buildEmbeddingText(event);
                embedEvent(event.getId(), text);
            });
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
