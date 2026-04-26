package dev.tylercash.event.rewind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;

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

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> svc.classify("Original"));
    }
}
