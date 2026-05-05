package dev.tylercash.event.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;

class DurableListenerRetryPollerTest {

    private ListenerInvocationRepository invocations;
    private PostCommitDispatcher dispatcher;
    private Clock clock;
    private SimpleMeterRegistry meterRegistry;
    private DurableListenerRetryPoller poller;

    private static final Instant NOW = Instant.parse("2026-05-04T10:00:00Z");

    @BeforeEach
    void setUp() {
        invocations = mock(ListenerInvocationRepository.class);
        dispatcher = mock(PostCommitDispatcher.class);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        meterRegistry = new SimpleMeterRegistry();
        poller = new DurableListenerRetryPoller(invocations, dispatcher, clock, meterRegistry);
    }

    // --- retry() tests ---

    @Test
    void retry_callsDispatcherForEachDueRow() {
        ListenerInvocation row1 = pendingRow(UUID.randomUUID(), "EventCreated", "listenerA");
        ListenerInvocation row2 = pendingRow(UUID.randomUUID(), "EventPublished", "listenerB");
        when(invocations.findDueForRetry(NOW, PageRequest.of(0, 100))).thenReturn(List.of(row1, row2));

        poller.retry();

        InOrder inOrder = Mockito.inOrder(dispatcher);
        inOrder.verify(dispatcher).invokeOnce(row1);
        inOrder.verify(dispatcher).invokeOnce(row2);
    }

    @Test
    void retry_emptyDueList_dispatcherNotCalled() {
        when(invocations.findDueForRetry(any(), any())).thenReturn(List.of());

        poller.retry();

        verify(dispatcher, never()).invokeOnce(any(ListenerInvocation.class));
    }

    @Test
    void retry_exceptionOnFirstRow_secondRowStillInvoked() {
        ListenerInvocation row1 = pendingRow(UUID.randomUUID(), "EventCreated", "listenerA");
        ListenerInvocation row2 = pendingRow(UUID.randomUUID(), "EventCreated", "listenerB");
        when(invocations.findDueForRetry(NOW, PageRequest.of(0, 100))).thenReturn(List.of(row1, row2));
        Mockito.doThrow(new RuntimeException("kaboom")).when(dispatcher).invokeOnce(row1);

        // Should not throw — exception is caught per-row
        poller.retry();

        verify(dispatcher, times(1)).invokeOnce(row1);
        verify(dispatcher, times(1)).invokeOnce(row2);
    }

    // --- emitStuckGauges() tests ---

    @Test
    void emitStuckGauges_countsStuckRowsByListener() {
        ListenerInvocation stuck1 = failedRow("listenerA", 25);
        ListenerInvocation stuck2 = failedRow("listenerA", 30);
        ListenerInvocation stuck3 = failedRow("listenerB", 26);
        ListenerInvocation notStuckAttempts = failedRow("listenerC", 10); // below threshold
        ListenerInvocation notStuckStatus = pendingRow(UUID.randomUUID(), "EventCreated", "listenerD"); // wrong status
        notStuckStatus.setAttempts(30);

        when(invocations.findAll()).thenReturn(List.of(stuck1, stuck2, stuck3, notStuckAttempts, notStuckStatus));

        poller.emitStuckGauges();

        // listenerA should have gauge = 2
        assertThat(meterRegistry
                        .find("event.lifecycle.listener.stuck")
                        .tag("listener", "listenerA")
                        .gauge())
                .isNotNull();
        assertThat(meterRegistry
                        .find("event.lifecycle.listener.stuck")
                        .tag("listener", "listenerA")
                        .gauge()
                        .value())
                .isEqualTo(2.0);

        // listenerB should have gauge = 1
        assertThat(meterRegistry
                        .find("event.lifecycle.listener.stuck")
                        .tag("listener", "listenerB")
                        .gauge()
                        .value())
                .isEqualTo(1.0);

        // listenerC and listenerD should not have gauges registered
        assertThat(meterRegistry
                        .find("event.lifecycle.listener.stuck")
                        .tag("listener", "listenerC")
                        .gauge())
                .isNull();
        assertThat(meterRegistry
                        .find("event.lifecycle.listener.stuck")
                        .tag("listener", "listenerD")
                        .gauge())
                .isNull();
    }

    @Test
    void emitStuckGauges_previouslyStuckListenerClearedToZero() {
        // First call: listenerA has stuck rows
        ListenerInvocation stuck1 = failedRow("listenerA", 25);
        when(invocations.findAll()).thenReturn(List.of(stuck1));
        poller.emitStuckGauges();

        assertThat(meterRegistry
                        .find("event.lifecycle.listener.stuck")
                        .tag("listener", "listenerA")
                        .gauge()
                        .value())
                .isEqualTo(1.0);

        // Second call: no more stuck rows for listenerA
        when(invocations.findAll()).thenReturn(List.of());
        poller.emitStuckGauges();

        // Gauge should be zeroed out, not removed
        assertThat(meterRegistry
                        .find("event.lifecycle.listener.stuck")
                        .tag("listener", "listenerA")
                        .gauge()
                        .value())
                .isEqualTo(0.0);
    }

    // --- helpers ---

    private static ListenerInvocation pendingRow(UUID eventId, String type, String listenerName) {
        ListenerInvocation row = new ListenerInvocation();
        row.setEventId(eventId);
        row.setLifecycleEventType(type);
        row.setListenerName(listenerName);
        row.setStatus(ListenerInvocationStatus.PENDING);
        row.setAttempts(0);
        return row;
    }

    private static ListenerInvocation failedRow(String listenerName, int attempts) {
        ListenerInvocation row = new ListenerInvocation();
        row.setEventId(UUID.randomUUID());
        row.setLifecycleEventType("EventCreated");
        row.setListenerName(listenerName);
        row.setStatus(ListenerInvocationStatus.FAILED);
        row.setAttempts(attempts);
        return row;
    }
}
