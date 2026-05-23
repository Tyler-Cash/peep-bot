package dev.tylercash.event.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * Decorates the JDBC session repository with two write-reduction behaviours:
 *
 * <ol>
 *   <li>Purely-anonymous sessions are never persisted. Mitigates pentest finding F-002:
 *       an unauthenticated caller used to create a fresh {@code SPRING_SESSION} row per
 *       request with the global TTL, a straightforward DB-fill DoS for anyone who can
 *       reach the API. "Purely anonymous" means no authenticated {@code SecurityContext}
 *       and no in-flight OAuth2 authorization request — such a session has nothing worth
 *       persisting past the response. The CSRF cookie is delivered out-of-band via
 *       {@code XSRF-TOKEN} and is independent of session storage.
 *   <li>Authenticated sessions whose attributes haven't changed are throttled: at most
 *       one UPDATE per {@link #SAVE_THROTTLE_WINDOW} per session. Spring Session writes
 *       {@code lastAccessTime} on every request, making {@code SPRING_SESSION} one of
 *       the hottest tables and a frequent source of UPDATE-vs-cleanup-cron lock
 *       contention. With a 30-day session timeout, lagging {@code EXPIRY_TIME} by a
 *       minute is harmless. As soon as any attribute changes (login completes,
 *       OAuth2 redirect state lands, security context flips), the snapshot fingerprint
 *       changes and the write happens immediately.
 * </ol>
 *
 * <p>{@code findById}, {@code deleteById}, and {@code findByIndexNameAndIndexValue} delegate
 * unchanged so authenticated reads, login upgrades, and logout-driven deletes all work
 * normally.
 *
 * <p>The generic parameter is {@link Session} (rather than the package-private {@code
 * JdbcIndexedSessionRepository.JdbcSession}) so this class can live outside the
 * {@code spring-session-jdbc} package.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class AnonymousSkippingSessionRepository implements FindByIndexNameSessionRepository<Session> {

    /** Spring Security's in-flight OAuth2 authorization-request attribute key. */
    private static final String OAUTH2_AUTHORIZATION_REQUEST_KEY =
            "org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository.AUTHORIZATION_REQUEST";

    /**
     * Maximum time between persisted writes for a session whose attributes haven't
     * changed. Trades idle-timeout precision (EXPIRY_TIME lags by up to this window) for
     * a ~Nx reduction in SPRING_SESSION UPDATE rate on busy sessions. 60s against a
     * 30-day session timeout is invisible.
     */
    private static final Duration SAVE_THROTTLE_WINDOW = Duration.ofSeconds(60);

    private final JdbcIndexedSessionRepository jdbc;
    private final SessionRepository<Session> delegate;

    /**
     * Per-session-id snapshot used to decide whether a save() can be skipped. Bounded to
     * keep memory usage finite under a session-id storm; eviction on overflow just means
     * the next save for that session won't be throttled, which is correct.
     */
    private final Cache<String, Snapshot> snapshots = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofDays(31))
            .build();

    AnonymousSkippingSessionRepository(JdbcIndexedSessionRepository delegate) {
        this.jdbc = delegate;
        // The generic-erased view exposes save(Session)/findById(String) signatures that accept
        // any Session subtype, which is what we need to forward through this wrapper without
        // referencing the package-private JdbcSession class directly.
        this.delegate = (SessionRepository<Session>) (SessionRepository) delegate;
    }

    @Override
    public Session createSession() {
        return delegate.createSession();
    }

    @Override
    public void save(Session session) {
        if (isAnonymous(session)) {
            // Drop the persist; the in-memory session continues to back the request, but no
            // SPRING_SESSION row is written. The CSRF cookie (XSRF-TOKEN) is already on the
            // response and is independent of session persistence.
            return;
        }
        if (canSkipSave(session)) {
            return;
        }
        delegate.save(session);
        snapshots.put(session.getId(), Snapshot.of(session));
    }

    @Override
    public Session findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public void deleteById(String id) {
        snapshots.invalidate(id);
        delegate.deleteById(id);
    }

    @Override
    public Map<String, Session> findByIndexNameAndIndexValue(String indexName, String indexValue) {
        return (Map) jdbc.findByIndexNameAndIndexValue(indexName, indexValue);
    }

    /**
     * Returns true when this session was persisted within {@link #SAVE_THROTTLE_WINDOW} and
     * its observable attribute set hasn't changed since. The cheap fingerprint is good
     * enough: any attribute change (new key, removed key, mutated value reference) shifts
     * the hash and forces a real save.
     */
    private boolean canSkipSave(Session session) {
        Snapshot prior = snapshots.getIfPresent(session.getId());
        if (prior == null) return false;
        if (Duration.between(prior.savedAt(), Instant.now()).compareTo(SAVE_THROTTLE_WINDOW) >= 0) {
            return false;
        }
        return prior.attrFingerprint() == fingerprint(session);
    }

    private static long fingerprint(Session session) {
        long h = 0L;
        Set<String> names = session.getAttributeNames();
        for (String name : names) {
            Object value = session.getAttribute(name);
            // XOR sum so iteration order doesn't matter. Per-attribute identity hash
            // (System.identityHashCode) would be safer against in-place mutation but
            // Spring's session attributes are conventionally replaced, not mutated.
            h ^= (long) name.hashCode() * 1469598103934665603L ^ (value == null ? 0L : value.hashCode());
        }
        return h;
    }

    private static boolean isAnonymous(Session session) {
        if (isAuthenticated(session)) {
            return false;
        }
        // OAuth2 callback needs to read the AUTHORIZATION_REQUEST it stashed on the redirect.
        // Other anonymous-only state (e.g. SPRING_SECURITY_SAVED_REQUEST) is deliberately not
        // persisted — losing it means an unauthenticated bounce-through-login forgets where
        // the user was headed, which is acceptable for the UX and required to deny the
        // DB-fill DoS.
        return session.getAttribute(OAUTH2_AUTHORIZATION_REQUEST_KEY) == null;
    }

    private static boolean isAuthenticated(Session session) {
        Object raw = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(raw instanceof SecurityContext context)) {
            return false;
        }
        Authentication authentication = context.getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }
        return authentication.isAuthenticated();
    }

    private record Snapshot(long attrFingerprint, Instant savedAt) {
        static Snapshot of(Session session) {
            return new Snapshot(fingerprint(session), Instant.now());
        }
    }
}
