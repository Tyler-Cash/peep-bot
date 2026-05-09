package dev.tylercash.event.security;

import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * Decorates the JDBC session repository so that purely-anonymous sessions are never persisted.
 * Mitigates pentest finding F-002: an unauthenticated caller (e.g. {@code GET /api/csrf}) used
 * to create a fresh {@code SPRING_SESSION} row per request with the global TTL, which is a
 * straightforward DB-fill DoS for anyone who can reach the API.
 *
 * <p>"Purely anonymous" means the session carries no authenticated {@code SecurityContext}
 * and no in-flight OAuth2 authorization request. Such a session has nothing worth keeping
 * past the response — the CSRF cookie is delivered out-of-band via {@code XSRF-TOKEN} and is
 * independent of session storage. As soon as a request authenticates or starts an OAuth2
 * login, the session contains state worth keeping and the underlying repository persists it
 * as before. {@code findById}, {@code deleteById}, and {@code findByIndexNameAndIndexValue}
 * delegate unchanged so authenticated reads, login upgrades, and logout-driven deletes all
 * work normally.
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

    private final JdbcIndexedSessionRepository jdbc;
    private final SessionRepository<Session> delegate;

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
        delegate.save(session);
    }

    @Override
    public Session findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public void deleteById(String id) {
        delegate.deleteById(id);
    }

    @Override
    public Map<String, Session> findByIndexNameAndIndexValue(String indexName, String indexValue) {
        return (Map) jdbc.findByIndexNameAndIndexValue(indexName, indexValue);
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
}
