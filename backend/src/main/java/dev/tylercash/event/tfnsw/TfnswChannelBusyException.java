package dev.tylercash.event.tfnsw;

/**
 * Raised when the per-channel ShedLock for a TfNSW notifier run is held by another
 * worker (different JVM, or a concurrent in-process invocation). Surfaces as a
 * retry on the durable EventCreated listener path; the week-before poller catches
 * it and logs, getting another chance on the next daily run. Transient by design.
 */
public class TfnswChannelBusyException extends RuntimeException {
    public TfnswChannelBusyException(long channelId) {
        super("TfNSW channel " + channelId + " is being processed by another worker");
    }
}
