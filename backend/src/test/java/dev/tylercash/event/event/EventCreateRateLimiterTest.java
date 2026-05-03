package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventCreateRateLimiterTest {

    private static EventCreateRateLimiter limiterWithCapacity(int perGuildPerHour) {
        EventCreateRateLimitConfiguration config = new EventCreateRateLimitConfiguration();
        config.setPerGuildPerHour(perGuildPerHour);
        return new EventCreateRateLimiter(config);
    }

    @Test
    void allowsUpToCapacityPerGuild() {
        EventCreateRateLimiter limiter = limiterWithCapacity(3);

        assertThat(limiter.tryAcquire(1L).ok()).isTrue();
        assertThat(limiter.tryAcquire(1L).ok()).isTrue();
        assertThat(limiter.tryAcquire(1L).ok()).isTrue();
        EventCreateRateLimiter.AcquireResult deniedResult = limiter.tryAcquire(1L);
        assertThat(deniedResult.ok()).isFalse();
        assertThat(deniedResult.retryAfterSeconds()).isPositive();
    }

    @Test
    void guildBucketsAreIndependent() {
        EventCreateRateLimiter limiter = limiterWithCapacity(1);

        assertThat(limiter.tryAcquire(1L).ok()).isTrue();
        assertThat(limiter.tryAcquire(1L).ok()).isFalse();
        assertThat(limiter.tryAcquire(2L).ok()).isTrue();
    }
}
