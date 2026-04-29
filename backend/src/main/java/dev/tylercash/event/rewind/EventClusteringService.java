package dev.tylercash.event.rewind;

import dev.tylercash.event.db.repository.EventCategoryRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.EventState;
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
    private final EventCategoryRepository eventCategoryRepository;

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
                eventCategoryRepository.upsertCategory(event.getId(), category);
                updated++;
            } catch (Exception e) {
                log.warn("Failed to classify event '{}': {}", event.getName(), e.getMessage());
            }
        }
        log.info("Updated {} event categories using fixed classification", updated);
    }
}
