package dev.tylercash.event.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.rate-limit")
public class RateLimitConfiguration {
    private int readCapacity = 60;
    private int readRefillSeconds = 60;
    private int writeCapacity = 20;
    private int writeRefillSeconds = 60;
}
