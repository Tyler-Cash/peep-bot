package dev.tylercash.event.rewind;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tylercash.event.db.repository.EventCategoryRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ClassifierBackfillRunnerTest {

    private final EventCategoryRepository categoryRepo = mock(EventCategoryRepository.class);
    private final EventRepository eventRepo = mock(EventRepository.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);

    @Test
    void runner_isNoOp_whenRunBeforeBlank() {
        var runner = new ClassifierBackfillRunner(categoryRepo, eventRepo, embeddingService, "", Duration.ZERO);

        runner.run(null);

        verify(categoryRepo, never()).findEventIdsAssignedBefore(any());
    }

    @Test
    void backfillLoop_classifiesEveryStreamedEvent() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Event e1 = new Event();
        e1.setId(id1);
        Event e2 = new Event();
        e2.setId(id2);

        when(categoryRepo.findEventIdsAssignedBefore(any())).thenReturn(Stream.of(id1, id2));
        when(eventRepo.findById(id1)).thenReturn(Optional.of(e1));
        when(eventRepo.findById(id2)).thenReturn(Optional.of(e2));

        var runner = new ClassifierBackfillRunner(
                categoryRepo, eventRepo, embeddingService, OffsetDateTime.now().toString(), Duration.ZERO);

        runner.backfillLoop();

        verify(embeddingService, times(1)).classifyEvent(e1);
        verify(embeddingService, times(1)).classifyEvent(e2);
    }

    @Test
    void backfillLoop_continues_whenSingleClassifyThrows() {
        UUID good = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        Event gE = new Event();
        gE.setId(good);
        Event bE = new Event();
        bE.setId(bad);

        when(categoryRepo.findEventIdsAssignedBefore(any())).thenReturn(Stream.of(bad, good));
        when(eventRepo.findById(bad)).thenReturn(Optional.of(bE));
        when(eventRepo.findById(good)).thenReturn(Optional.of(gE));
        doThrow(new RuntimeException("ollama down")).when(embeddingService).classifyEvent(bE);

        var runner = new ClassifierBackfillRunner(
                categoryRepo, eventRepo, embeddingService, OffsetDateTime.now().toString(), Duration.ZERO);

        runner.backfillLoop();

        verify(embeddingService, times(1)).classifyEvent(gE);
    }
}
