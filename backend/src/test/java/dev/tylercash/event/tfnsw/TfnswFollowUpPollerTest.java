package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TfnswFollowUpPollerTest {
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

    private TfnswEventSnapshotRepository snapshots;
    private TfnswOrchestrator orchestrator;
    private TfnswConfiguration cfg;
    private TfnswFollowUpPoller poller;

    @BeforeEach
    void setUp() {
        snapshots = mock(TfnswEventSnapshotRepository.class);
        orchestrator = mock(TfnswOrchestrator.class);
        cfg = new TfnswConfiguration();
        poller = new TfnswFollowUpPoller(snapshots, orchestrator, cfg);
    }

    @Test
    void queriesEventsAtConfiguredLeadDays() {
        cfg.setFollowUpLeadDays(3);
        when(snapshots.findEventIdsForFollowUpCheck(any(), any())).thenReturn(List.of());

        poller.run();

        ArgumentCaptor<ZonedDateTime> from = ArgumentCaptor.forClass(ZonedDateTime.class);
        ArgumentCaptor<ZonedDateTime> to = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(snapshots).findEventIdsForFollowUpCheck(from.capture(), to.capture());
        assertThat(from.getValue().toLocalDate())
                .isEqualTo(LocalDate.now(SYDNEY).plusDays(3));
        assertThat(to.getValue()).isEqualTo(from.getValue().plusDays(1));
    }

    @Test
    void invokesOrchestratorForEachEventInWindow() {
        when(snapshots.findEventIdsForFollowUpCheck(any(), any())).thenReturn(List.of());
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(snapshots.findEventIdsForFollowUpCheck(any(), any())).thenReturn(List.of(a, b));

        poller.run();

        verify(orchestrator).process(eq(a), eq(true));
        verify(orchestrator).process(eq(b), eq(true));
    }

    @Test
    void swallowsPerEventExceptionsAndContinues() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(snapshots.findEventIdsForFollowUpCheck(any(), any())).thenReturn(List.of(a, b));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(orchestrator)
                .process(eq(a), eq(true));

        poller.run(); // must not throw

        verify(orchestrator).process(eq(b), eq(true));
    }
}
