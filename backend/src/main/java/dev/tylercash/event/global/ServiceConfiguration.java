package dev.tylercash.event.global;

import dev.tylercash.event.immich.ImmichConfiguration;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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

    @Bean
    public Retry immichUploadRetry() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofSeconds(1), 2.0))
                .retryOnException(e -> e instanceof RestClientException && !(e instanceof HttpClientErrorException))
                .build();
        return Retry.of("immichUpload", config);
    }

    @Bean
    public RestClient immichRestClient(ImmichConfiguration immichConfiguration) {
        return RestClient.builder()
                .baseUrl(immichConfiguration.getBaseUrl() != null ? immichConfiguration.getBaseUrl() : "")
                .defaultHeader(
                        "x-api-key", immichConfiguration.getApiKey() != null ? immichConfiguration.getApiKey() : "")
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
