package dev.tylercash.event.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;

class PostCommitDispatcherTest {

    // SyncTaskExecutor implements TaskExecutor which extends Executor.
    // AsyncTaskExecutor also extends Executor, so we can wrap it.
    private static final org.springframework.core.task.AsyncTaskExecutor SYNC_EXECUTOR =
            command -> new SyncTaskExecutor().execute(command);

    private ListenerInvocationRepository repo;
    private BackoffPolicy backoff;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DurableEventListener<EventLifecycleEvent.EventCreated> matchingListener;

    private PostCommitDispatcher dispatcher;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        repo = mock(ListenerInvocationRepository.class);
        // Default: claim succeeds (1 row updated). Tests that want a contested claim override this.
        when(repo.claim(any(), any(), any(), any())).thenReturn(1);
        // Default: state-transition UPDATEs succeed (1 row updated).
        when(repo.markSuccess(any(), any(), any(), any())).thenReturn(1);
        when(repo.markFailed(any(), any(), any(), any(), any(), any())).thenReturn(1);
        backoff = new BackoffPolicy();
        matchingListener = mock(DurableEventListener.class);
        when(matchingListener.name()).thenReturn("Test Listener");
        when(matchingListener.eventType()).thenReturn(EventLifecycleEvent.EventCreated.class);
        // Pass-through invoker — no Spring context needed for unit tests
        DurableListenerInvoker invoker = new DurableListenerInvoker();
        dispatcher = new PostCommitDispatcher(
                List.of(matchingListener),
                repo,
                SYNC_EXECUTOR,
                backoff,
                invoker,
                io.micrometer.observation.ObservationRegistry.NOOP);
    }

    @Test
    void onCommit_successPath_rowSavedWithSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        EventLifecycleEvent.EventCreated event = new EventLifecycleEvent.EventCreated(id);
        ListenerInvocation row = pendingRow(id, "EventCreated", "Test Listener");
        when(repo.findById(new ListenerInvocationId(id, "EventCreated", "Test Listener")))
                .thenReturn(Optional.of(row));

        dispatcher.onCommit(event);

        ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repo).markSuccess(eq(id), eq("EventCreated"), eq("Test Listener"), nowCaptor.capture());
        assertThat(nowCaptor.getValue()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
        verify(repo, never()).markFailed(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onCommit_failurePath_rowSavedWithFailedAndBackoff() throws Exception {
        UUID id = UUID.randomUUID();
        EventLifecycleEvent.EventCreated event = new EventLifecycleEvent.EventCreated(id);
        ListenerInvocation row = pendingRow(id, "EventCreated", "Test Listener");
        when(repo.findById(new ListenerInvocationId(id, "EventCreated", "Test Listener")))
                .thenReturn(Optional.of(row));
        when(matchingListener.eventType()).thenReturn(EventLifecycleEvent.EventCreated.class);
        // Throw on handle
        RuntimeException cause = new RuntimeException("boom");
        org.mockito.Mockito.doThrow(cause).when(matchingListener).handle(any());

        dispatcher.onCommit(event);

        ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> nextRetryCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repo)
                .markFailed(
                        eq(id),
                        eq("EventCreated"),
                        eq("Test Listener"),
                        nowCaptor.capture(),
                        nextRetryCaptor.capture(),
                        contains("boom"));
        assertThat(nowCaptor.getValue()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
        // nextRetryAt should be approximately now + 1 minute (first attempt)
        assertThat(nextRetryCaptor.getValue()).isCloseTo(Instant.now().plusSeconds(60), within(5, ChronoUnit.SECONDS));
        verify(repo, never()).markSuccess(any(), any(), any(), any());
    }

    @Test
    void invokeOnce_skipsRowAlreadySucceeded() throws Exception {
        UUID id = UUID.randomUUID();
        EventLifecycleEvent.EventCreated event = new EventLifecycleEvent.EventCreated(id);
        ListenerInvocation row = pendingRow(id, "EventCreated", "Test Listener");
        row.setStatus(ListenerInvocationStatus.SUCCESS);

        dispatcher.invokeOnce(row, event);

        verify(repo, never()).markSuccess(any(), any(), any(), any());
        verify(repo, never()).markFailed(any(), any(), any(), any(), any(), any());
        verify(matchingListener, never()).handle(any());
    }

    @Test
    void invokeOnce_skipsWhenClaimContested() throws Exception {
        // Simulates the dispatcher/retry-poller race: another worker has already claimed the row,
        // so claim() updates 0 rows. We must not invoke the listener (otherwise downstream
        // publish() would re-insert the next outbox row and crash on the PK constraint).
        when(repo.claim(any(), any(), any(), any())).thenReturn(0);
        UUID id = UUID.randomUUID();
        EventLifecycleEvent.EventCreated event = new EventLifecycleEvent.EventCreated(id);
        ListenerInvocation row = pendingRow(id, "EventCreated", "Test Listener");

        dispatcher.invokeOnce(row, event);

        verify(matchingListener, never()).handle(any());
        verify(repo, never()).markSuccess(any(), any(), any(), any());
        verify(repo, never()).markFailed(any(), any(), any(), any(), any(), any());
    }

    @Test
    void markSuccess_zeroRowsUpdated_doesNotThrow() throws Exception {
        // Defensive: if a retry-poller reclaim races with our markSuccess (the row was reset back
        // to PENDING under us), the UPDATE matches 0 rows. We log and move on rather than retry —
        // the poller will pick it back up.
        when(repo.markSuccess(any(), any(), any(), any())).thenReturn(0);
        UUID id = UUID.randomUUID();
        EventLifecycleEvent.EventCreated event = new EventLifecycleEvent.EventCreated(id);
        ListenerInvocation row = pendingRow(id, "EventCreated", "Test Listener");
        when(repo.findById(new ListenerInvocationId(id, "EventCreated", "Test Listener")))
                .thenReturn(Optional.of(row));

        assertThatCode(() -> dispatcher.onCommit(event)).doesNotThrowAnyException();
    }

    @Test
    void reconstructEvent_knownType_returnsCorrectRecord() {
        UUID id = UUID.randomUUID();
        ListenerInvocation row = pendingRow(id, "EventCreated", "Test Listener");

        EventLifecycleEvent event = PostCommitDispatcher.reconstructEvent(row);

        assertThat(event).isInstanceOf(EventLifecycleEvent.EventCreated.class);
        assertThat(event.eventId()).isEqualTo(id);
    }

    @Test
    void reconstructEvent_unknownType_returnsNull() {
        UUID id = UUID.randomUUID();
        ListenerInvocation row = pendingRow(id, "NonExistentEventType", "Test Listener");

        EventLifecycleEvent event = PostCommitDispatcher.reconstructEvent(row);

        assertThat(event).isNull();
    }

    @Test
    void onCommit_nonMatchingEvent_doesNotInvokeListener() throws Exception {
        UUID id = UUID.randomUUID();
        // Publish an EventArchived — our listener only matches EventCreated
        EventLifecycleEvent.EventArchived event = new EventLifecycleEvent.EventArchived(id);

        dispatcher.onCommit(event);

        verify(repo, never()).findById(any());
        verify(matchingListener, never()).handle(any());
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
}
