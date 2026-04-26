package dev.tylercash.event.rewind;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TextNormalisationService {

    private final OllamaChatModel chatModel;
    private final RewindConfiguration config;

    private static final String CLASSIFY_PROMPT_TEMPLATE =
            """
            Classify this event into exactly one of the following categories: %s. \
            Reply with the category name only. No explanation, no punctuation.

            Event: "%s"
            Category:""";

    public TextNormalisationService(
            @Autowired(required = false) OllamaChatModel chatModel, RewindConfiguration config) {
        this.chatModel = chatModel;
        this.config = config;
        if (chatModel != null) {
            log.info("TextNormalisationService initialized — LLM normalisation available");
        }
    }

    public boolean isAvailable() {
        return chatModel != null;
    }

    public String classify(String eventName) {
        if (!isAvailable()) {
            throw new IllegalStateException("LLM service is not available for classification");
        }
        try {
            String categories = String.join(", ", config.getCategories());
            String prompt = String.format(CLASSIFY_PROMPT_TEMPLATE, categories, eventName.replace("\"", "'"));
            String response = callModel(prompt);

            return config.getCategories().stream()
                    .filter(c -> c.equalsIgnoreCase(response))
                    .findFirst()
                    .orElse("unknown");
        } catch (Exception e) {
            log.error("Classification failed for event: {}", e.getMessage());
            throw e;
        }
    }

    private String callModel(String prompt) {
        String response = chatModel.call(prompt).trim();
        return response.replaceAll("^[\"'`]|[\"'`]$", "").trim();
    }
}
