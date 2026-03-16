package dev.tylercash.event.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final int READ_BUCKET = 0;
    private static final int WRITE_BUCKET = 1;

    private final RateLimitConfiguration config;
    private final Cache<String, Bucket[]> bucketCache;

    public RateLimitFilter(RateLimitConfiguration config) {
        this.config = config;
        this.bucketCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/actuator/") || path.startsWith("/swagger-ui/") || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String key = resolveKey(request);
        Bucket[] buckets = bucketCache.get(key, k -> createBuckets());

        boolean isRead = READ_METHODS.contains(request.getMethod().toUpperCase());
        Bucket bucket = buckets[isRead ? READ_BUCKET : WRITE_BUCKET];
        int limit = isRead ? config.getReadCapacity() : config.getWriteCapacity();

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
            filterChain.doFilter(request, response);
        } else {
            long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
            log.warn(
                    "Rate limit exceeded for key={} method={} path={} retryAfter={}s",
                    key,
                    request.getMethod(),
                    request.getServletPath(),
                    retryAfterSeconds);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Too many requests\",\"retryAfter\":" + retryAfterSeconds + "}");
        }
    }

    private String resolveKey(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            return "session:" + session.getId();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private Bucket[] createBuckets() {
        return new Bucket[] {
            Bucket.builder()
                    .addLimit(Bandwidth.builder()
                            .capacity(config.getReadCapacity())
                            .refillGreedy(config.getReadCapacity(), Duration.ofSeconds(config.getReadRefillSeconds()))
                            .build())
                    .build(),
            Bucket.builder()
                    .addLimit(Bandwidth.builder()
                            .capacity(config.getWriteCapacity())
                            .refillGreedy(config.getWriteCapacity(), Duration.ofSeconds(config.getWriteRefillSeconds()))
                            .build())
                    .build()
        };
    }
}
