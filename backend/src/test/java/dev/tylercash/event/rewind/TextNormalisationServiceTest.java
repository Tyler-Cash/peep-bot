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

    @Test
    @DisplayName("classify returns the matching category from configured list when LLM responds with it")
    void classify_returnsMatchingCategory() {
        org.springframework.ai.ollama.OllamaChatModel chatModel =
                org.mockito.Mockito.mock(org.springframework.ai.ollama.OllamaChatModel.class);
        org.springframework.ai.chat.messages.AssistantMessage assistant =
                new org.springframework.ai.chat.messages.AssistantMessage("Show");
        org.springframework.ai.chat.model.Generation gen = new org.springframework.ai.chat.model.Generation(assistant);
        org.springframework.ai.chat.model.ChatResponse chatResponse =
                new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(gen));
        org.mockito.Mockito.when(chatModel.call(
                        org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(chatResponse);

        TextNormalisationService svc = new TextNormalisationService(chatModel, config());

        dev.tylercash.event.event.model.Event event = new dev.tylercash.event.event.model.Event();
        event.setName("A live comedy night with stand-up performers");

        String category = svc.classify(event);

        assertThat(category).isEqualTo("Show");
    }

    @Test
    @DisplayName("classify returns unknown when LLM responds with a category not in the configured list")
    void classify_returnsUnknownForOffListResponse() {
        org.springframework.ai.ollama.OllamaChatModel chatModel =
                org.mockito.Mockito.mock(org.springframework.ai.ollama.OllamaChatModel.class);
        org.springframework.ai.chat.messages.AssistantMessage assistant =
                new org.springframework.ai.chat.messages.AssistantMessage("Cabaret");
        org.springframework.ai.chat.model.Generation gen = new org.springframework.ai.chat.model.Generation(assistant);
        org.springframework.ai.chat.model.ChatResponse chatResponse =
                new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(gen));
        org.mockito.Mockito.when(chatModel.call(
                        org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(chatResponse);

        TextNormalisationService svc = new TextNormalisationService(chatModel, config());

        dev.tylercash.event.event.model.Event event = new dev.tylercash.event.event.model.Event();
        event.setName("Edge-case event that doesn't fit");

        String category = svc.classify(event);

        assertThat(category).isEqualTo("unknown");
    }
}
