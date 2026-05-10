package dev.tylercash.event.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
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
        backoff = new BackoffPolicy();
        matchingListener = mock(DurableEventListener.class);
        when(matchingListener.name()).thenReturn("Test Listener");
        when(matchingListener.eventType()).thenReturn(EventLifecycleEvent.EventCreated.class);
        // Pass-through invoker — no Spring context needed for unit tests
        DurableListenerInvoker invoker = new DurableListenerInvoker();
        dispatcher = new PostCommitDispatcher(List.of(matchingListener), repo, SYNC_EXECUTOR, backoff, invoker);
    }

    @Test
    void onCommit_successPath_rowSavedWithSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        EventLifecycleEvent.EventCreated event = new EventLifecycleEvent.EventCreated(id);
        ListenerInvocation row = pendingRow(id, "EventCreated", "Test Listener");
        when(repo.findById(new ListenerInvocationId(id, "EventCreated", "Test Listener")))
                .thenReturn(Optional.of(row));

        dispatcher.onCommit(event);

        ArgumentCaptor<ListenerInvocation> captor = ArgumentCaptor.forClass(ListenerInvocation.class);
        verify(repo).save(captor.capture());
        ListenerInvocation saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ListenerInvocationStatus.SUCCESS);
        assertThat(saved.getLastError()).isNull();
        assertThat(saved.getLastAttemptAt()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
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

        ArgumentCaptor<ListenerInvocation> captor = ArgumentCaptor.forClass(ListenerInvocation.class);
        verify(repo).save(captor.capture());
        ListenerInvocation saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ListenerInvocationStatus.FAILED);
        assertThat(saved.getAttempts()).isEqualTo(1);
        assertThat(saved.getLastError()).contains("boom");
        // nextRetryAt should be approximately now + 1 minute (first attempt)
        assertThat(saved.getNextRetryAt()).isCloseTo(Instant.now().plusSeconds(60), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void invokeOnce_skipsRowAlreadySucceeded() throws Exception {
        UUID id = UUID.randomUUID();
        EventLifecycleEvent.EventCreated event = new EventLifecycleEvent.EventCreated(id);
        ListenerInvocation row = pendingRow(id, "EventCreated", "Test Listener");
        row.setStatus(ListenerInvocationStatus.SUCCESS);

        dispatcher.invokeOnce(row, event);

        verify(repo, never()).save(any());
        verify(matchingListener, never()).handle(any());
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
