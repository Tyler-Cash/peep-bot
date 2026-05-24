package dev.tylercash.event.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.test.SharedPostgres;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Guards the invariant: a JDBC {@code connection} observation must never be a trace root — every
 * connection acquisition must run inside a parent observation.
 *
 * <p>Spring Session JDBC's {@code JdbcIndexedSessionRepository} schedules
 * {@code cleanUpExpiredSessions} on a private {@code ThreadPoolTaskScheduler} of its own, outside
 * Spring's {@code @Scheduled} observation instrumentation. Its {@code DELETE FROM SPRING_SESSION}
 * therefore acquired a connection with no observation in scope, and datasource-micrometer emitted
 * that connection as a standalone root trace ("orphan"). The fix disables that internal scheduler
 * ({@code @EnableJdbcHttpSession(cleanupCron = "-")}) and re-drives cleanup from
 * {@link SessionCleanupJob} (an {@code @Observed @Scheduled} bean).
 */
@SpringBootTest(
        classes = PeepBotApplication.class,
        properties = {
            "spring.security.oauth2.client.registration.discord.client-id=test",
            "spring.security.oauth2.client.registration.discord.client-secret=test",
            "dev.tylercash.discord.token=dummy",
            "dev.tylercash.discord.guild-id=0",
            "dev.tylercash.frontend.hostname=test.local",
            "dev.tylercash.rate-limit.read-capacity=10000",
            "dev.tylercash.rate-limit.write-capacity=10000"
        })
@ActiveProfiles("local")
class SessionCleanupObservationTest {

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private SessionCleanupJob sessionCleanupJob;

    @Autowired
    @Qualifier("jdbcIndexedSessionRepository")
    private JdbcIndexedSessionRepository repository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.registerProperties(registry);
    }

    @Test
    void sessionCleanup_runsInsideAnObservation_neverAnOrphanRoot() {
        List<Observation.Context> jdbcConnections = new CopyOnWriteArrayList<>();
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return "jdbc.connection".equals(context.getName());
            }

            @Override
            public void onStart(Observation.Context context) {
                jdbcConnections.add(context);
            }
        });

        // Production entry point for expired-session cleanup after the fix: the @Observed
        // @Scheduled bean. Always issues a DELETE FROM SPRING_SESSION, so it always acquires a
        // connection.
        sessionCleanupJob.cleanUpExpiredSessions();

        assertThat(jdbcConnections)
                .as("session cleanup must acquire at least one JDBC connection")
                .isNotEmpty();
        assertThat(jdbcConnections).allSatisfy(context -> assertThat(context.getParentObservation())
                .as("a JDBC connection must have a parent observation — it must never start its own "
                        + "trace as an orphan root")
                .isNotNull());
    }

    @Test
    void springSessionInternalCleanupScheduler_isDisabled() {
        // With cleanupCron = "-", JdbcIndexedSessionRepository.afterPropertiesSet() skips creating
        // its private scheduler, leaving the field null. This is what stops the unobserved,
        // every-minute cleanup that produced orphan connection traces.
        assertThat(ReflectionTestUtils.getField(repository, "taskScheduler"))
                .as("Spring Session's private cleanup scheduler must be disabled; cleanup is owned "
                        + "by SessionCleanupJob so it runs inside an observation")
                .isNull();
    }
}
