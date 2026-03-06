package dev.tylercash.event.global;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.feature-toggles")
public class FeatureTogglesConfiguration {
    private boolean removePlusOnesOnDecline = true;
}
