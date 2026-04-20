package dev.tylercash.event.rewind;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TextNormalisationService {

    private static final String PROMPT_TEMPLATE =
            """
            Extract only the activity type from this event name. Reply with 1 to 3 words only. \
            No venue, no location, no day, no explanation, no punctuation.

            Event: "Group dinner at a restaurant"
            Activity: Dinner

            Event: "Weekly games night at the community centre"
            Activity: Games Night

            Event: "Outdoor cinema screening"
            Activity: Movie Night

            Event: "%s"
            Activity:""";

    private final OllamaChatModel chatModel;
    private final RewindConfiguration config;

    public TextNormalisationService(
            @Autowired(required = false) OllamaChatModel chatModel, RewindConfiguration config) {
        this.chatModel = chatModel;
        this.config = config;
        if (chatModel != null) {
            log.info("TextNormalisationService initialized — LLM normalisation available");
        }
    }

    public boolean isAvailable() {
        return chatModel != null && config.isNormalisationEnabled();
    }

    public String normalise(String eventName) {
        if (!isAvailable()) {
            return eventName;
        }
        try {
            String prompt = String.format(PROMPT_TEMPLATE, eventName.replace("\"", "'"));
            String response = chatModel.call(prompt).trim();
            response = response.replaceAll("^[\"'`]|[\"'`]$", "").trim();
            if (response.isBlank() || response.length() > 50) {
                return eventName;
            }
            return response;
        } catch (Exception e) {
            log.debug("Normalisation failed for event: {}", e.getMessage());
            return eventName;
        }
    }
}
