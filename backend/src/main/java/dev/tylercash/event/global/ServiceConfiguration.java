package dev.tylercash.event.global;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class ServiceConfiguration {

    public static final String NOTIFY_EVENT_ROLES_KEY = "notifyEventRoles";

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public RateLimiterConfig rateLimiterConfig() {
        return RateLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(1))
                .limitRefreshPeriod(Duration.ofHours(12))
                .limitForPeriod(3)
                .build();
    }


    @Bean(NOTIFY_EVENT_ROLES_KEY)
    public RateLimiter notifyEventRoles(RateLimiterConfig config) {
        return RateLimiter.of(NOTIFY_EVENT_ROLES_KEY, config);
    }
}
