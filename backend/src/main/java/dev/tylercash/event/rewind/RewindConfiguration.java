package dev.tylercash.event.rewind;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.rewind")
public class RewindConfiguration {
    private boolean enabled = true;
    private int backfillBatchSize = 20;
    private java.util.List<String> categories = java.util.List.of("Food", "Movie", "Game", "Outdoor", "Trivia");
}
