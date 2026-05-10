package dev.tylercash.event.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Tiny @Retryable shim around {@link DurableEventListener#handle}. Lives in its own bean so
 * Spring's AOP proxy can intercept the call from {@link PostCommitDispatcher} (self-invocation
 * would bypass the advice).
 *
 * <p>Optimistic-lock collisions between concurrent listeners writing the same Event row used to
 * surface as FAILED outbox rows that only converged on the 60-second retry-poller cycle. With this
 * in-line retry they converge in hundreds of ms, keeping integration tests deterministic and prod
 * recovery tight.
 */
@Component
@Slf4j
public class DurableListenerInvoker {

    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 500))
    public <E extends EventLifecycleEvent> void invoke(DurableEventListener<E> listener, E event) throws Exception {
        listener.handle(event);
    }
}
