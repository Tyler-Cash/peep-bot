package dev.tylercash.event.rewind;

import dev.tylercash.event.event.model.Event;
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

            Event name: "%s"
            Location: "%s"
            Date: %s
            Description: "%s"
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

    public String classify(Event event) {
        if (!isAvailable()) {
            throw new IllegalStateException("LLM service is not available for classification");
        }
        try {
            String categories = String.join(", ", config.getCategories());
            String name = event.getName().replace("\"", "'");
            String location = event.getLocation() != null ? event.getLocation().replace("\"", "'") : "";
            String date = event.getDateTime() != null ? event.getDateTime().toLocalDate().toString() : "unknown";
            String description = event.getDescription() != null ? event.getDescription().replace("\"", "'") : "";
            String prompt = String.format(CLASSIFY_PROMPT_TEMPLATE, categories, name, location, date, description);
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
