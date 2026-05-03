package dev.tylercash.event.event;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Per-guild rate limit on event creation. Each guild may store its own override on the {@code guild} row; when not
 * set, falls back to {@link EventCreateRateLimitConfiguration#getPerGuildPerHour()}. Buckets are cached in-memory; a
 * settings change MUST call {@link #invalidate(long)} so the next request constructs a fresh bucket with the new
 * capacity.
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
    private final GuildRepository guildRepository;
    private final Cache<Long, Bucket> guildBuckets;

    public EventCreateRateLimiter(EventCreateRateLimitConfiguration config, GuildRepository guildRepository) {
        this.config = config;
        this.guildRepository = guildRepository;
        this.guildBuckets = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(2, TimeUnit.HOURS)
                .build();
    }

    public void invalidate(long guildId) {
        guildBuckets.invalidate(guildId);
    }

    public AcquireResult tryAcquire(long guildId) {
        Bucket bucket = guildBuckets.get(guildId, this::buildBucket);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return AcquireResult.allowed();
        }
        long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
        log.warn("Event-create rate limit exceeded for guild {}, retry in {}s", guildId, retryAfterSeconds);
        return AcquireResult.denied(retryAfterSeconds);
    }

    private Bucket buildBucket(long guildId) {
        int capacity = guildRepository
                .findById(guildId)
                .map(Guild::getEventCreateRateLimitPerHour)
                .filter(value -> value != null)
                .orElse(config.getPerGuildPerHour());
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, Duration.ofHours(1))
                        .build())
                .build();
    }
}
