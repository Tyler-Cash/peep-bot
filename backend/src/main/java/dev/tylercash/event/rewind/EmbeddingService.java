package dev.tylercash.event.rewind;

import dev.tylercash.event.db.repository.EventEmbeddingRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.rewind.model.EventEmbedding;
import java.time.OffsetDateTime;
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
    private final EventEmbeddingRepository embeddingRepository;

    public EmbeddingService(
            @Autowired(required = false) EmbeddingModel embeddingModel,
            RewindConfiguration config,
            EventRepository eventRepository,
            EventEmbeddingRepository embeddingRepository) {
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

    String buildEmbeddingText(Event event) {
        var sb = new StringBuilder(event.getName());
        sb.append(" | ").append(event.getDateTime().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        if (event.getDescription() != null
                && !event.getDescription().isBlank()
                && !event.getDescription()
                        .trim()
                        .equalsIgnoreCase(event.getName().trim())) {
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
            float[] raw = embeddingModel.embed(nameText);
            embeddingRepository.save(new EventEmbedding(eventId, nameText, toVectorString(raw), OffsetDateTime.now()));
        } catch (Exception e) {
            log.error("Failed to embed event {}: {}", eventId, e.getMessage());
        }
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
            eventRepository.findById(id).ifPresent(event -> embedEvent(event.getId(), buildEmbeddingText(event)));
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
