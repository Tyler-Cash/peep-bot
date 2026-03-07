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
    @DisplayName("CREATED state maps to INIT_CHANNEL signal")
    void created() {
        assertEquals(List.of(EventStateMachineEvent.INIT_CHANNEL), poller.signalsForState(EventState.CREATED));
    }

    @Test
    @DisplayName("INIT_CHANNEL state maps to INIT_ROLES signal")
    void initChannel() {
        assertEquals(List.of(EventStateMachineEvent.INIT_ROLES), poller.signalsForState(EventState.INIT_CHANNEL));
    }

    @Test
    @DisplayName("INIT_ROLES state maps to INIT_COMPLETE signal")
    void initRoles() {
        assertEquals(List.of(EventStateMachineEvent.INIT_COMPLETE), poller.signalsForState(EventState.INIT_ROLES));
    }

    @Test
    @DisplayName("PLANNED state maps to PRE_EVENT_NOTIFY signal")
    void planned() {
        assertEquals(List.of(EventStateMachineEvent.PRE_EVENT_NOTIFY), poller.signalsForState(EventState.PLANNED));
    }

    @Test
    @DisplayName("PRE_NOTIFIED state maps to PREPARE_ALBUM and COMPLETE signals")
    void preNotified() {
        List<EventStateMachineEvent> signals = poller.signalsForState(EventState.PRE_NOTIFIED);
        assertEquals(2, signals.size());
        assertEquals(EventStateMachineEvent.PREPARE_ALBUM, signals.get(0));
        assertEquals(EventStateMachineEvent.COMPLETE, signals.get(1));
    }

    @Test
    @DisplayName("POST_ALBUM_READY state maps to POST_ALBUM and COMPLETE signals")
    void postAlbumReady() {
        List<EventStateMachineEvent> signals = poller.signalsForState(EventState.POST_ALBUM_READY);
        assertEquals(2, signals.size());
        assertEquals(EventStateMachineEvent.POST_ALBUM, signals.get(0));
        assertEquals(EventStateMachineEvent.COMPLETE, signals.get(1));
    }

    @Test
    @DisplayName("POST_ALBUM_SHARED state maps to COMPLETE signal")
    void postAlbumShared() {
        assertEquals(List.of(EventStateMachineEvent.COMPLETE), poller.signalsForState(EventState.POST_ALBUM_SHARED));
    }

    @Test
    @DisplayName("POST_COMPLETED state maps to ARCHIVE signal")
    void postCompleted() {
        assertEquals(List.of(EventStateMachineEvent.ARCHIVE), poller.signalsForState(EventState.POST_COMPLETED));
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
