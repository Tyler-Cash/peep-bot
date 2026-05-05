package dev.tylercash.event.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {
    private final BackoffPolicy policy = new BackoffPolicy();

    @Test
    void firstAttemptDelaysOneMinute() {
        assertDelay(1, Duration.ofMinutes(1));
    }

    @Test
    void secondAttemptDelaysTwoMinutes() {
        assertDelay(2, Duration.ofMinutes(2));
    }

    @Test
    void thirdAttemptDelaysFourMinutes() {
        assertDelay(3, Duration.ofMinutes(4));
    }

    @Test
    void eighthAttemptDelays128Minutes() {
        assertDelay(8, Duration.ofMinutes(128));
    }

    @Test
    void capsAtFourHours() {
        assertDelay(20, Duration.ofHours(4));
    }

    private void assertDelay(int attempts, Duration expected) {
        Instant before = Instant.now();
        Instant next = policy.nextRetryAt(attempts);
        Duration delay = Duration.between(before, next);
        // Tolerate ±1s for clock jitter in test execution
        assertThat(delay).isBetween(expected.minusSeconds(1), expected.plusSeconds(1));
    }
}
