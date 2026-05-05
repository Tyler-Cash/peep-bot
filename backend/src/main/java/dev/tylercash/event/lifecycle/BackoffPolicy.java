package dev.tylercash.event.lifecycle;

import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class BackoffPolicy {
    private static final Duration BASE = Duration.ofMinutes(1);
    private static final Duration CAP = Duration.ofHours(4);

    /**
     * Returns the wall-clock instant of the next retry given the current attempt count (1 = just
     * had the first failure). Doubles each attempt, capped at 4h.
     */
    public Instant nextRetryAt(int attempts) {
        // attempts=1 → 1m, =2 → 2m, =3 → 4m, =8 → 128m, =9 → 256m capped to 240m
        long shift = Math.min(Math.max(attempts - 1, 0), 30);
        Duration delay = Duration.ofMinutes(BASE.toMinutes() << shift);
        if (delay.compareTo(CAP) > 0) delay = CAP;
        return Instant.now().plus(delay);
    }
}
