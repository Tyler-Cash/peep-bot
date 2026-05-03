package dev.tylercash.event.places;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.places")
public class PlacesConfiguration {
    private String apiKey = "";
    private int maxHeightPx = 720;

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @PostConstruct
    public void log() {
        log.info("Google Places integration {}", isEnabled() ? "enabled" : "disabled (no api-key configured)");
    }
}
