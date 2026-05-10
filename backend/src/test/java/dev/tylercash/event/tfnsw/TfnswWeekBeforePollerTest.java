package dev.tylercash.event.tfnsw;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TfnswWeekBeforePollerTest {

    @Test
    void invokesOrchestratorForEachEventInWindow() {
        TfnswEventSnapshotRepository snapshots = mock(TfnswEventSnapshotRepository.class);
        TfnswOrchestrator orchestrator = mock(TfnswOrchestrator.class);
        TfnswWeekBeforePoller poller = new TfnswWeekBeforePoller(snapshots, orchestrator);

        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(snapshots.findEventIdsForWeekBeforeCheck(any(), any())).thenReturn(List.of(a, b));

        poller.run();

        verify(orchestrator).process(eq(a), eq(true));
        verify(orchestrator).process(eq(b), eq(true));
    }

    @Test
    void swallowsPerEventExceptionsAndContinues() {
        TfnswEventSnapshotRepository snapshots = mock(TfnswEventSnapshotRepository.class);
        TfnswOrchestrator orchestrator = mock(TfnswOrchestrator.class);
        TfnswWeekBeforePoller poller = new TfnswWeekBeforePoller(snapshots, orchestrator);

        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(snapshots.findEventIdsForWeekBeforeCheck(any(), any())).thenReturn(List.of(a, b));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(orchestrator)
                .process(eq(a), eq(true));

        poller.run(); // must not throw

        verify(orchestrator).process(eq(b), eq(true));
    }
}
