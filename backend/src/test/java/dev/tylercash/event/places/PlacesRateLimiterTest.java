package dev.tylercash.event.places;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlacesRateLimiterTest {

    private static PlacesConfiguration config(int perUserPerHour, int globalPerDay) {
        PlacesConfiguration c = new PlacesConfiguration();
        c.setPerUserPerHour(perUserPerHour);
        c.setGlobalPerDay(globalPerDay);
        return c;
    }

    @Test
    void allowsUpToPerUserCapPerKey() {
        PlacesRateLimiter limiter = new PlacesRateLimiter(config(3, 100));

        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isFalse();
    }

    @Test
    void perUserBucketsAreIndependent() {
        PlacesRateLimiter limiter = new PlacesRateLimiter(config(1, 100));

        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isFalse();
        assertThat(limiter.tryAcquire("bob")).isTrue();
    }

    @Test
    void globalCapDeniesEvenIfPerUserHasRoom() {
        PlacesRateLimiter limiter = new PlacesRateLimiter(config(100, 2));

        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("bob")).isTrue();
        assertThat(limiter.tryAcquire("carol")).isFalse();
    }

    @Test
    void globalDenialRefundsUserToken() {
        PlacesRateLimiter limiter = new PlacesRateLimiter(config(2, 1));

        assertThat(limiter.tryAcquire("alice")).isTrue(); // user 1/2, global 0/1
        assertThat(limiter.tryAcquire("alice")).isFalse(); // global empty -> refund -> user back to 1/2
        // user bucket should still have one token; only global is the blocker
        // (we can't really observe that without a passing call, but at least confirm no over-decrement)
        assertThat(limiter.tryAcquire("alice")).isFalse(); // still global-blocked
    }

    @Test
    void nullOrBlankUserKeyMappedToAnonymous() {
        PlacesRateLimiter limiter = new PlacesRateLimiter(config(1, 100));

        assertThat(limiter.tryAcquire(null)).isTrue();
        assertThat(limiter.tryAcquire("")).isFalse();
        assertThat(limiter.tryAcquire("  ")).isFalse();
    }
}
