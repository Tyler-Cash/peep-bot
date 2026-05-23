package dev.tylercash.event.rewind;

import dev.tylercash.event.db.repository.EventCategoryRepository;
import dev.tylercash.event.db.repository.EventRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-classifies events whose category was assigned before a cutoff timestamp. Gated by the
 * property {@code peepbot.classifier.backfill.run-before}; when unset, the runner is a no-op.
 *
 * <p>Designed for one-off use after a prompt change. Set the property to the deploy timestamp,
 * deploy, watch the log line per 50 events, then unset the property on the next deploy.
 */
@Slf4j
@Component
public class ClassifierBackfillRunner implements ApplicationRunner {

    private final EventCategoryRepository categoryRepository;
    private final EventRepository eventRepository;
    private final EmbeddingService embeddingService;
    private final OffsetDateTime runBefore;
    private final Duration pacing;

    public ClassifierBackfillRunner(
            EventCategoryRepository categoryRepository,
            EventRepository eventRepository,
            EmbeddingService embeddingService,
            @Value("${peepbot.classifier.backfill.run-before:}") String runBefore,
            @Value("${peepbot.classifier.backfill.pacing:PT1S}") Duration pacing) {
        this.categoryRepository = categoryRepository;
        this.eventRepository = eventRepository;
        this.embeddingService = embeddingService;
        this.runBefore = runBefore == null || runBefore.isBlank() ? null : OffsetDateTime.parse(runBefore);
        this.pacing = pacing;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (runBefore == null) {
            return;
        }
        log.info("Classifier backfill: starting, cutoff={} pacing={}", runBefore, pacing);
        // Don't block startup — fire and forget on a single dedicated thread.
        Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "classifier-backfill");
                    t.setDaemon(true);
                    return t;
                })
                .submit(this::backfillLoop);
    }

    @Transactional(readOnly = true)
    void backfillLoop() {
        long processed = 0;
        try (var ids = categoryRepository.findEventIdsAssignedBefore(runBefore)) {
            for (var iter = ids.iterator(); iter.hasNext(); ) {
                var id = iter.next();
                try {
                    var event = eventRepository.findById(id).orElse(null);
                    if (event != null) {
                        embeddingService.classifyEvent(event);
                    }
                } catch (Exception e) {
                    log.warn("Classifier backfill: reclassify failed for event {}: {}", id, e.getMessage());
                }
                processed++;
                if (processed % 50 == 0) {
                    log.info("Classifier backfill: {} events processed", processed);
                }
                try {
                    Thread.sleep(pacing.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.info("Classifier backfill: interrupted at {} events", processed);
                    return;
                }
            }
        }
        log.info("Classifier backfill: done, {} events processed", processed);
    }
}
