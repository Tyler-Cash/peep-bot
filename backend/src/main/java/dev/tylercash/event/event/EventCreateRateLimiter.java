package dev.tylercash.event.event;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Per-guild rate limit on event creation. Prevents one chatty server from spamming events while leaving other guilds
 * unaffected. Independent of the per-session/IP rate limit applied by {@code RateLimitFilter}.
 */
@Slf4j
@Component
public class EventCreateRateLimiter {

    public record AcquireResult(boolean ok, long retryAfterSeconds) {
        public static AcquireResult allowed() {
            return new AcquireResult(true, 0);
        }

        public static AcquireResult denied(long retryAfterSeconds) {
            return new AcquireResult(false, retryAfterSeconds);
        }
    }

    private final EventCreateRateLimitConfiguration config;
    private final Cache<Long, Bucket> guildBuckets;

    public EventCreateRateLimiter(EventCreateRateLimitConfiguration config) {
        this.config = config;
        this.guildBuckets = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(2, TimeUnit.HOURS)
                .build();
    }

    public AcquireResult tryAcquire(long guildId) {
        Bucket bucket = guildBuckets.get(guildId, k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(config.getPerGuildPerHour())
                        .refillGreedy(config.getPerGuildPerHour(), Duration.ofHours(1))
                        .build())
                .build());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return AcquireResult.allowed();
        }
        long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
        log.warn("Event-create rate limit exceeded for guild {}, retry in {}s", guildId, retryAfterSeconds);
        return AcquireResult.denied(retryAfterSeconds);
    }
}
