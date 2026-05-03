package dev.tylercash.event.places;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Caps Places API spend with two limits checked together: a per-creator hourly bucket and a global daily bucket. If
 * either bucket is empty, the call is denied — the event still saves but the cover photo is skipped until the bucket
 * refills.
 */
@Slf4j
@Component
public class PlacesRateLimiter {
    private final PlacesConfiguration config;
    private final Cache<String, Bucket> userBuckets;
    private final Bucket globalBucket;

    public PlacesRateLimiter(PlacesConfiguration config) {
        this.config = config;
        this.userBuckets = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(2, TimeUnit.HOURS)
                .build();
        this.globalBucket = Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(config.getGlobalPerDay())
                        .refillGreedy(config.getGlobalPerDay(), Duration.ofDays(1))
                        .build())
                .build();
    }

    public boolean tryAcquire(String userKey) {
        String key = (userKey == null || userKey.isBlank()) ? "anonymous" : userKey;
        Bucket userBucket = userBuckets.get(key, k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(config.getPerUserPerHour())
                        .refillGreedy(config.getPerUserPerHour(), Duration.ofHours(1))
                        .build())
                .build());

        if (!userBucket.tryConsume(1)) {
            log.warn("Places per-user rate limit exceeded for user {}", key);
            return false;
        }
        if (!globalBucket.tryConsume(1)) {
            // refund user token so they're not penalised for our global cap
            userBucket.addTokens(1);
            log.warn("Places global rate limit exceeded; denying fetch for user {}", key);
            return false;
        }
        return true;
    }
}
