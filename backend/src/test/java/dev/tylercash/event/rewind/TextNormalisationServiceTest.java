package dev.tylercash.event.rewind;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextNormalisationServiceTest {

    private RewindConfiguration config() {
        return new RewindConfiguration();
    }

    @Test
    @DisplayName("isAvailable is false when no chat model is wired in")
    void isAvailable_falseWithoutModel() {
        TextNormalisationService svc = new TextNormalisationService(null, config());

        assertThat(svc.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("classify throws IllegalStateException when no chat model is wired in")
    void classify_throwsWhenUnavailable() {
        TextNormalisationService svc = new TextNormalisationService(null, config());

        dev.tylercash.event.event.model.Event event = new dev.tylercash.event.event.model.Event();
        event.setName("Original");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> svc.classify(event));
    }
}
