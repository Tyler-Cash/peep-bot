package dev.tylercash.event.lifecycle;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(classes = {DurableListenerInvoker.class, DurableListenerInvokerTest.RetryConfig.class})
@ContextConfiguration
class DurableListenerInvokerTest {

    @EnableRetry
    static class RetryConfig {}

    @Autowired
    DurableListenerInvoker invoker;

    @Test
    @SuppressWarnings("unchecked")
    void retriesOnOptimisticLockFailureUntilSuccess() throws Exception {
        DurableEventListener<EventLifecycleEvent.EventCreated> listener = mock(DurableEventListener.class);
        EventLifecycleEvent.EventCreated event = new EventLifecycleEvent.EventCreated(UUID.randomUUID());

        // Fail twice, then succeed
        doThrow(new ObjectOptimisticLockingFailureException("Event", UUID.randomUUID()))
                .doThrow(new ObjectOptimisticLockingFailureException("Event", UUID.randomUUID()))
                .doNothing()
                .when(listener)
                .handle(any());

        invoker.invoke(listener, event);

        verify(listener, org.mockito.Mockito.times(3)).handle(event);
    }

    @Test
    @SuppressWarnings("unchecked")
    void givesUpAfterMaxAttempts() throws Exception {
        DurableEventListener<EventLifecycleEvent.EventCreated> listener = mock(DurableEventListener.class);
        EventLifecycleEvent.EventCreated event = new EventLifecycleEvent.EventCreated(UUID.randomUUID());

        doThrow(new ObjectOptimisticLockingFailureException("Event", UUID.randomUUID()))
                .when(listener)
                .handle(any());

        assertThatThrownBy(() -> invoker.invoke(listener, event))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(listener, org.mockito.Mockito.times(3)).handle(event);
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotRetryOnUnrelatedException() throws Exception {
        DurableEventListener<EventLifecycleEvent.EventCreated> listener = mock(DurableEventListener.class);
        EventLifecycleEvent.EventCreated event = new EventLifecycleEvent.EventCreated(UUID.randomUUID());

        doThrow(new IllegalStateException("nope")).when(listener).handle(any());

        assertThatThrownBy(() -> invoker.invoke(listener, event)).isInstanceOf(IllegalStateException.class);

        verify(listener, org.mockito.Mockito.times(1)).handle(event);
    }
}
