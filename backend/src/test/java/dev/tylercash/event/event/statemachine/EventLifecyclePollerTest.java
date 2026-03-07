package dev.tylercash.event.event.statemachine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tylercash.event.event.model.EventState;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventLifecyclePollerTest {

    private final EventLifecyclePoller poller = new EventLifecyclePoller(null, null, ObservationRegistry.NOOP);

    @Test
    @DisplayName("PLANNED state maps to PRE_EVENT_NOTIFY signal")
    void planned() {
        assertEquals(List.of(EventStateMachineEvent.PRE_EVENT_NOTIFY), poller.signalsForState(EventState.PLANNED));
    }

    @Test
    @DisplayName("NOTIFIED state maps to PREPARE_ALBUM and COMPLETE signals")
    void notified() {
        List<EventStateMachineEvent> signals = poller.signalsForState(EventState.NOTIFIED);
        assertEquals(2, signals.size());
        assertEquals(EventStateMachineEvent.PREPARE_ALBUM, signals.get(0));
        assertEquals(EventStateMachineEvent.COMPLETE, signals.get(1));
    }

    @Test
    @DisplayName("ALBUM_READY state maps to POST_ALBUM and COMPLETE signals")
    void albumReady() {
        List<EventStateMachineEvent> signals = poller.signalsForState(EventState.ALBUM_READY);
        assertEquals(2, signals.size());
        assertEquals(EventStateMachineEvent.POST_ALBUM, signals.get(0));
        assertEquals(EventStateMachineEvent.COMPLETE, signals.get(1));
    }

    @Test
    @DisplayName("ALBUM_POSTED state maps to COMPLETE signal")
    void albumPosted() {
        assertEquals(List.of(EventStateMachineEvent.COMPLETE), poller.signalsForState(EventState.ALBUM_POSTED));
    }

    @Test
    @DisplayName("COMPLETED state maps to ARCHIVE signal")
    void completed() {
        assertEquals(List.of(EventStateMachineEvent.ARCHIVE), poller.signalsForState(EventState.COMPLETED));
    }

    @Test
    @DisplayName("ARCHIVED state maps to DELETE signal")
    void archived() {
        assertEquals(List.of(EventStateMachineEvent.DELETE), poller.signalsForState(EventState.ARCHIVED));
    }

    @Test
    @DisplayName("DELETED state maps to empty list")
    void deleted() {
        assertTrue(poller.signalsForState(EventState.DELETED).isEmpty());
    }
}
