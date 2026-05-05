package dev.tylercash.event.lifecycle;

/**
 * A listener invoked when a lifecycle event matching {@link #eventType()} is published.
 * The publisher creates a {@link ListenerInvocation} outbox row in the publishing
 * transaction; if the row reaches SUCCESS, this listener will not be invoked again
 * for the same (event_id, lifecycle_event_type) tuple.
 *
 * <p>Implementations MUST be idempotent: a listener may be re-invoked after a partial
 * crash. Use "find by external ID, create if missing" or conditional state updates.
 */
public interface DurableEventListener<E extends EventLifecycleEvent> {

    /**
     * Stable, human-readable identifier. Used as the outbox row key AND rendered in
     * logs / dashboards / admin UI. Title Case with spaces — e.g. "Immich Album Prep".
     * Renaming is a data migration. Must be unique across all DurableEventListener beans.
     */
    String name();

    Class<E> eventType();

    /** Throw to signal failure; the dispatcher will record FAILED + schedule retry. */
    void handle(E event) throws Exception;
}
