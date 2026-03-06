package dev.tylercash.event.immich;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Log4j2
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.immich")
public class ImmichConfiguration {
    private String baseUrl;
    private String apiKey;
    private boolean enabled = false;
    private String albumNamePrefix = "";

    @PostConstruct
    public void validate() {
        if (enabled) {
            log.info("Immich integration enabled with base URL: {}", baseUrl);
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException("Immich is enabled but 'dev.tylercash.immich.base-url' is not configured");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("Immich is enabled but 'dev.tylercash.immich.api-key' is not configured");
            }
        } else {
            log.info("Immich integration disabled");
        }
    }
}
