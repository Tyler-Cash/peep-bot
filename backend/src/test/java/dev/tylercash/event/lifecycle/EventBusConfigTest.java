package dev.tylercash.event.lifecycle;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class EventBusConfigTest {

    @Test
    void validateListenerNamesAreUnique_duplicateNames_throwsIllegalState() {
        DurableEventListener<?> a = stubListener("Duplicate Name");
        DurableEventListener<?> b = stubListener("Duplicate Name");
        EventBusConfig config = new EventBusConfig(List.of(a, b));

        assertThatThrownBy(config::validateListenerNamesAreUnique)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate DurableEventListener name: Duplicate Name");
    }

    @Test
    void validateListenerNamesAreUnique_distinctNames_noException() {
        DurableEventListener<?> a = stubListener("Listener Alpha");
        DurableEventListener<?> b = stubListener("Listener Beta");
        EventBusConfig config = new EventBusConfig(List.of(a, b));

        assertThatNoException().isThrownBy(config::validateListenerNamesAreUnique);
    }

    @Test
    void validateListenerNamesAreUnique_emptyList_noException() {
        EventBusConfig config = new EventBusConfig(List.of());

        assertThatNoException().isThrownBy(config::validateListenerNamesAreUnique);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DurableEventListener<?> stubListener(String name) {
        DurableEventListener mock = mock(DurableEventListener.class);
        when(mock.name()).thenReturn(name);
        return mock;
    }
}
