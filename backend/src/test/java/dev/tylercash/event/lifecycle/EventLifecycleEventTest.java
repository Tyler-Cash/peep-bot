package dev.tylercash.event.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventLifecycleEventTest {

    private static final UUID EVENT_ID = UUID.randomUUID();

    // --- Lifecycle progression ---

    @Test
    @DisplayName("EventCreated exposes eventId()")
    void eventCreated_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventCreated(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("EventChannelReady exposes eventId()")
    void eventChannelReady_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventChannelReady(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("EventRolesReady exposes eventId()")
    void eventRolesReady_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventRolesReady(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("EventClassified exposes eventId()")
    void eventClassified_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventClassified(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("EventPlanned exposes eventId()")
    void eventPlanned_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventPlanned(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("EventPreNotified exposes eventId()")
    void eventPreNotified_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventPreNotified(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("EventCompleted exposes eventId()")
    void eventCompleted_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventCompleted(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("EventArchived exposes eventId()")
    void eventArchived_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventArchived(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    // --- Branching paths ---

    @Test
    @DisplayName("EventCancelRequested exposes eventId()")
    void eventCancelRequested_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventCancelRequested(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("EventCancelled exposes eventId()")
    void eventCancelled_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventCancelled(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("EventDeleteRequested exposes eventId()")
    void eventDeleteRequested_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventDeleteRequested(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("EventDeleted exposes eventId()")
    void eventDeleted_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventDeleted(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    // --- Time-based ---

    @Test
    @DisplayName("EventPreNotifyDue exposes eventId()")
    void eventPreNotifyDue_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventPreNotifyDue(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("EventCompletionDue exposes eventId()")
    void eventCompletionDue_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventCompletionDue(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("EventArchivalDue exposes eventId()")
    void eventArchivalDue_exposesEventId() {
        EventLifecycleEvent event = new EventLifecycleEvent.EventArchivalDue(EVENT_ID);
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
    }
}
