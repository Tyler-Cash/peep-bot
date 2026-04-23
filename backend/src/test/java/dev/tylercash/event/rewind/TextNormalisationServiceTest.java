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

    private RewindConfiguration enabledConfig() {
        RewindConfiguration cfg = new RewindConfiguration();
        cfg.setNormalisationEnabled(true);
        return cfg;
    }

    private RewindConfiguration disabledConfig() {
        return new RewindConfiguration();
    }

    @Test
    @DisplayName("isAvailable is false when no chat model is wired in")
    void isAvailable_falseWithoutModel() {
        TextNormalisationService svc = new TextNormalisationService(null, enabledConfig());

        assertThat(svc.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("isAvailable is false when the model is present but normalisation is disabled")
    void isAvailable_falseWhenDisabledInConfig() {
        OllamaChatModel model = mock(OllamaChatModel.class);
        TextNormalisationService svc = new TextNormalisationService(model, disabledConfig());

        assertThat(svc.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("normalise returns the original name when the service is disabled and never calls the model")
    void normalise_returnsOriginalWhenDisabled() {
        OllamaChatModel model = mock(OllamaChatModel.class);
        TextNormalisationService svc = new TextNormalisationService(model, disabledConfig());

        String result = svc.normalise("Board game night");

        assertThat(result).isEqualTo("Board game night");
        verifyNoInteractions(model);
    }

    @Test
    @DisplayName("normalise trims whitespace and surrounding quotes from the model's response")
    void normalise_stripsQuotesAndWhitespace() {
        OllamaChatModel model = mock(OllamaChatModel.class);
        when(model.call(anyString())).thenReturn("  \"Dinner\"  ");
        TextNormalisationService svc = new TextNormalisationService(model, enabledConfig());

        assertThat(svc.normalise("Group dinner at a restaurant")).isEqualTo("Dinner");
    }

    @Test
    @DisplayName("normalise falls back to the original name when the model returns blank output")
    void normalise_fallsBackOnBlankResponse() {
        OllamaChatModel model = mock(OllamaChatModel.class);
        when(model.call(anyString())).thenReturn("   ");
        TextNormalisationService svc = new TextNormalisationService(model, enabledConfig());

        assertThat(svc.normalise("Original")).isEqualTo("Original");
    }

    @Test
    @DisplayName("normalise falls back to the original name when the model response exceeds 50 chars")
    void normalise_fallsBackOnOverlyLongResponse() {
        OllamaChatModel model = mock(OllamaChatModel.class);
        when(model.call(anyString())).thenReturn("a".repeat(51));
        TextNormalisationService svc = new TextNormalisationService(model, enabledConfig());

        assertThat(svc.normalise("Original")).isEqualTo("Original");
    }

    @Test
    @DisplayName("normalise swallows exceptions from the model and returns the original name")
    void normalise_swallowsExceptionsAndReturnsOriginal() {
        OllamaChatModel model = mock(OllamaChatModel.class);
        when(model.call(anyString())).thenThrow(new RuntimeException("boom"));
        TextNormalisationService svc = new TextNormalisationService(model, enabledConfig());

        assertThat(svc.normalise("Original")).isEqualTo("Original");
    }
}
