package dev.tylercash.event.tfnsw;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.tylercash.event.lifecycle.EventLifecycleEvent;
import dev.tylercash.event.lifecycle.listener.TfnswEventCreatedListener;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TfnswEventCreatedListenerTest {

    @Test
    void delegatesToOrchestratorWithRecheckFalse() throws Exception {
        TfnswOrchestrator orchestrator = mock(TfnswOrchestrator.class);
        TfnswEventCreatedListener listener = new TfnswEventCreatedListener(orchestrator);

        UUID id = UUID.randomUUID();
        listener.handle(new EventLifecycleEvent.EventCreated(id));

        verify(orchestrator).process(id, false);
    }
}
