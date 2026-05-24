package dev.tylercash.event.security;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Component;

/**
 * Drives Spring Session JDBC's expired-session cleanup from an application {@code @Scheduled}
 * method so the {@code DELETE FROM SPRING_SESSION} runs inside an observation.
 *
 * <p>Left to itself, {@link JdbcIndexedSessionRepository} schedules {@code cleanUpExpiredSessions}
 * on a <em>private</em> {@code ThreadPoolTaskScheduler} it creates in its own
 * {@code afterPropertiesSet} — outside Spring's {@code @Scheduled} observation instrumentation. The
 * cleanup then acquired a JDBC connection with no observation in scope, and datasource-micrometer
 * emitted that connection as a standalone root trace (an "orphan"). Spring Session's internal
 * scheduler is disabled via {@code @EnableJdbcHttpSession(cleanupCron = "-")} in
 * {@link WebSecurityConfig}; this bean re-drives cleanup so the connection nests under a
 * {@code tasks.scheduled.execution} → {@code session.cleanup} parent span.
 *
 * <p>This also restores the intended cadence: the {@code spring.session.jdbc.cleanup-cron} property
 * never bound (manual {@code @EnableJdbcHttpSession} opts out of Boot's session auto-config), so
 * cleanup had been silently running on Spring Session's every-minute default rather than every
 * 10 minutes.
 */
@Component
@RequiredArgsConstructor
public class SessionCleanupJob {

    // Field name matches the bean name so injection is unambiguous — there are two
    // JdbcIndexedSessionRepository beans (the @EnableJdbcHttpSession-registered "sessionRepository"
    // and the app's explicit "jdbcIndexedSessionRepository"); both share the SPRING_SESSION table,
    // so cleaning via either purges all expired rows.
    private final JdbcIndexedSessionRepository jdbcIndexedSessionRepository;

    @Observed(name = "session.cleanup")
    @Scheduled(cron = "0 */10 * * * *")
    public void cleanUpExpiredSessions() {
        jdbcIndexedSessionRepository.cleanUpExpiredSessions();
    }
}
