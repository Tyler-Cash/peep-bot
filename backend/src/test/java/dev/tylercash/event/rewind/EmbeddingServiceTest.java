package dev.tylercash.event.rewind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.tylercash.event.db.repository.EventEmbeddingRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.rewind.model.EventEmbedding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

class EmbeddingServiceTest {
    private EmbeddingModel embeddingModel;
    private TextNormalisationService normalisationService;
    private RewindConfiguration config;
    private EventRepository eventRepository;
    private EventEmbeddingRepository embeddingRepository;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        normalisationService = mock(TextNormalisationService.class);
        config = new RewindConfiguration();
        eventRepository = mock(EventRepository.class);
        embeddingRepository = mock(EventEmbeddingRepository.class);
    }

    private EmbeddingService service(EmbeddingModel model) {
        return new EmbeddingService(model, normalisationService, config, eventRepository, embeddingRepository);
    }

    @Test
    @DisplayName("isEmbeddingsAvailable is false when no model is wired in")
    void isEmbeddingsAvailable_falseWithoutModel() {
        assertThat(service(null).isEmbeddingsAvailable()).isFalse();
    }

    @Test
    @DisplayName("isEmbeddingsAvailable is false when the model is present but config disables it")
    void isEmbeddingsAvailable_falseWhenConfigDisabled() {
        config.setEnabled(false);

        assertThat(service(embeddingModel).isEmbeddingsAvailable()).isFalse();
    }

    @Test
    @DisplayName("embedEvent is a no-op when embeddings are unavailable")
    void embedEvent_noopWhenUnavailable() {
        EmbeddingService svc = service(null);

        svc.embedEvent(UUID.randomUUID(), "Dinner");

        verifyNoInteractions(embeddingRepository);
    }

    @Test
    @DisplayName("embedEvent persists the embedding with a comma-separated bracketed vector string")
    void embedEvent_savesVectorInPgVectorFormat() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.5f, -0.25f, 1.0f});
        EmbeddingService svc = service(embeddingModel);
        UUID eventId = UUID.randomUUID();

        svc.embedEvent(eventId, "Dinner");

        ArgumentCaptor<EventEmbedding> captor = ArgumentCaptor.forClass(EventEmbedding.class);
        verify(embeddingRepository).save(captor.capture());
        EventEmbedding saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo(eventId);
        assertThat(saved.getNameText()).isEqualTo("Dinner");
        assertThat(saved.getEmbedding()).isEqualTo("[0.5,-0.25,1.0]");
        assertThat(saved.getComputedAt()).isNotNull();
    }

    @Test
    @DisplayName("embedEvent swallows embedding failures and does not persist a partial record")
    void embedEvent_swallowsFailures() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("model down"));
        EmbeddingService svc = service(embeddingModel);

        svc.embedEvent(UUID.randomUUID(), "Dinner");

        verify(embeddingRepository, never()).save(any());
    }

    @Test
    @DisplayName("backfillMissingEmbeddings is a no-op when embeddings are unavailable")
    void backfill_noopWhenUnavailable() {
        EmbeddingService svc = service(null);

        svc.backfillMissingEmbeddings();

        verifyNoInteractions(embeddingRepository);
    }

    @Test
    @DisplayName("backfillMissingEmbeddings embeds each event returned from the missing-ids query")
    void backfill_embedsEachMissingEvent() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(embeddingRepository.findEventIdsWithoutEmbedding(anyInt())).thenReturn(List.of(id1, id2));

        Event e1 = new Event();
        e1.setId(id1);
        e1.setName("Brunch");
        Event e2 = new Event();
        e2.setId(id2);
        e2.setName("Hike");
        when(eventRepository.findById(id1)).thenReturn(Optional.of(e1));
        when(eventRepository.findById(id2)).thenReturn(Optional.of(e2));
        when(normalisationService.normalise("Brunch")).thenReturn("Brunch");
        when(normalisationService.normalise("Hike")).thenReturn("Hike");
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f});

        service(embeddingModel).backfillMissingEmbeddings();

        verify(embeddingRepository, org.mockito.Mockito.times(2)).save(any(EventEmbedding.class));
    }

    @Test
    @DisplayName("backfillMissingEmbeddings is a no-op when no events are missing embeddings")
    void backfill_noopWhenNoMissing() {
        when(embeddingRepository.findEventIdsWithoutEmbedding(anyInt())).thenReturn(List.of());

        service(embeddingModel).backfillMissingEmbeddings();

        verify(embeddingRepository, never()).save(any());
        verifyNoInteractions(eventRepository);
    }
}
