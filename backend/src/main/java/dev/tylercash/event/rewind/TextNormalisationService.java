package dev.tylercash.event.rewind;

import dev.tylercash.event.event.model.Event;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

@Slf4j
@Service
public class TextNormalisationService {

    private static final String DEFAULT_PROMPT_RESOURCE = "classifier/prompt-v1.txt";

    private final OllamaChatModel chatModel;
    private final RewindConfiguration config;
    private final String defaultPromptTemplate;

    public TextNormalisationService(
            @Autowired(required = false) OllamaChatModel chatModel, RewindConfiguration config) {
        this.chatModel = chatModel;
        this.config = config;
        this.defaultPromptTemplate = loadPromptTemplate(DEFAULT_PROMPT_RESOURCE);
        if (chatModel != null) {
            log.info("TextNormalisationService initialized — LLM normalisation available");
        }
    }

    public boolean isAvailable() {
        return chatModel != null;
    }

    public String classify(Event event) {
        return classify(event, defaultPromptTemplate);
    }

    /** Overload used by the eval harness to swap prompt variants without restarting. */
    public String classify(Event event, String promptTemplate) {
        if (!isAvailable()) {
            throw new IllegalStateException("LLM service is not available for classification");
        }
        try {
            String prompt = renderPrompt(promptTemplate, event);
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

    /** Package-private so the eval harness can preload templates without a service instance. */
    static String loadPromptTemplate(String classpathResource) {
        try {
            ClassPathResource resource = new ClassPathResource(classpathResource);
            return new String(FileCopyUtils.copyToByteArray(resource.getInputStream()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load prompt template: " + classpathResource, e);
        }
    }

    private String renderPrompt(String template, Event event) {
        String categories = String.join(", ", config.getCategories());
        String name = event.getName().replace("\"", "'");
        String location = event.getLocation() != null ? event.getLocation().replace("\"", "'") : "";
        String date =
                event.getDateTime() != null ? event.getDateTime().toLocalDate().toString() : "unknown";
        String description =
                event.getDescription() != null ? event.getDescription().replace("\"", "'") : "";
        return template.replace("{categories}", categories)
                .replace("{name}", name)
                .replace("{location}", location)
                .replace("{date}", date)
                .replace("{description}", description);
    }

    private String callModel(String prompt) {
        String response = chatModel.call(prompt).trim();
        return response.replaceAll("^[\"'`]|[\"'`]$", "").trim();
    }
}
