package dev.tylercash.event.lifecycle;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
public class PostCommitDispatcher {

    static final long INVOCATION_TIMEOUT_SECONDS = 60;

    private final ListenerInvocationRepository invocations;
    private final AsyncTaskExecutor executor;
    private final BackoffPolicy backoff;
    private final Map<String, DurableEventListener<?>> byName;
    private final List<DurableEventListener<?>> listeners;

    public PostCommitDispatcher(
            List<DurableEventListener<?>> listeners,
            ListenerInvocationRepository invocations,
            AsyncTaskExecutor eventBusExecutor,
            BackoffPolicy backoff) {
        this.listeners = listeners;
        this.invocations = invocations;
        this.executor = eventBusExecutor;
        this.backoff = backoff;
        Map<String, DurableEventListener<?>> map = new HashMap<>();
        for (DurableEventListener<?> l : listeners) map.put(l.name(), l);
        this.byName = Map.copyOf(map);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommit(EventLifecycleEvent event) {
        String type = event.getClass().getSimpleName();
        for (DurableEventListener<?> listener : listeners) {
            if (!listener.eventType().isInstance(event)) continue;
            ListenerInvocationId id = new ListenerInvocationId(event.eventId(), type, listener.name());
            invocations.findById(id).ifPresent(row -> invokeOnce(row, event));
        }
    }

    /**
     * Re-invoke a single outbox row asynchronously, reconstructing the event from the row. Used by
     * the retry poller when it has no in-memory event instance.
     */
    void invokeOnce(ListenerInvocation row) {
        EventLifecycleEvent event = reconstructEvent(row);
        if (event == null) return;
        invokeOnce(row, event);
    }

    /**
     * Re-invoke a single outbox row asynchronously. Used by the dispatcher itself AND by the retry
     * poller when the caller already holds the event instance.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void invokeOnce(ListenerInvocation row, EventLifecycleEvent event) {
        if (row.getStatus() == ListenerInvocationStatus.SUCCESS) return;
        DurableEventListener listener = byName.get(row.getListenerName());
        if (listener == null) {
            log.error("No listener registered for name '{}'", row.getListenerName());
            return;
        }
        CompletableFuture.runAsync(
                        () -> {
                            try {
                                listener.handle(event);
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        },
                        executor)
                .orTimeout(INVOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((v, t) -> {
                    if (t == null) markSuccess(row);
                    else markFailed(row, rootCauseMessage(t));
                });
    }

    /**
     * Reconstruct an {@link EventLifecycleEvent} from the data stored in the outbox row. The event
     * type is stored as the record's simple name (e.g. {@code "EventCreated"}), which maps to a
     * nested record inside {@link EventLifecycleEvent} via the {@code $} separator.
     *
     * @return the reconstructed event, or {@code null} if the type is no longer recognised (e.g. a
     *     record was removed after rows were written).
     */
    static EventLifecycleEvent reconstructEvent(ListenerInvocation row) {
        try {
            Class<?> cls = Class.forName(EventLifecycleEvent.class.getName() + "$" + row.getLifecycleEventType());
            return (EventLifecycleEvent)
                    cls.getConstructor(java.util.UUID.class).newInstance(row.getEventId());
        } catch (ReflectiveOperationException e) {
            log.error(
                    "Cannot reconstruct event type '{}' for row {}/{} — skipping",
                    row.getLifecycleEventType(),
                    row.getEventId(),
                    row.getListenerName());
            return null;
        }
    }

    private void markSuccess(ListenerInvocation row) {
        row.setStatus(ListenerInvocationStatus.SUCCESS);
        row.setLastAttemptAt(Instant.now());
        row.setLastError(null);
        invocations.save(row);
    }

    private void markFailed(ListenerInvocation row, String error) {
        row.setStatus(ListenerInvocationStatus.FAILED);
        row.setAttempts(row.getAttempts() + 1);
        row.setLastAttemptAt(Instant.now());
        row.setNextRetryAt(backoff.nextRetryAt(row.getAttempts()));
        row.setLastError(error);
        invocations.save(row);
        log.warn("Listener '{}' failed for event {}: {}", row.getListenerName(), row.getEventId(), error);
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
