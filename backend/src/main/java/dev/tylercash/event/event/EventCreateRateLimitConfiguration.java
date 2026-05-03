package dev.tylercash.event.event;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.event.create-rate-limit")
public class EventCreateRateLimitConfiguration {
    private int perGuildPerHour = 5;
}
