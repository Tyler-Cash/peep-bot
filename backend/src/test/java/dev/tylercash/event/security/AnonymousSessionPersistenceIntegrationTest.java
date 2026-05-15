package dev.tylercash.event.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.test.SharedPostgres;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Pentest finding F-002: anonymous calls used to provision a fresh
 * {@code SPRING_SESSION} row per request, with the global multi-day TTL.
 * Five unauthenticated {@code GET /csrf} calls must leave the table empty —
 * the CSRF cookie itself is independent of session persistence and is the
 * only deliverable of that endpoint.
 *
 * <p>This is the one test that owns the global {@code SPRING_SESSION} state:
 * it asserts the table is empty after anonymous probes, so it has to clean
 * up sibling-class sessions in {@code @BeforeEach}. SPRING_SESSION rows are
 * pure framework state (no application FKs reference them), so wiping is safe.
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
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AnonymousSessionPersistenceIntegrationTest {

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Isolated DB so other test classes' authenticated sessions can't end up in our
        // SPRING_SESSION table during the test window. The F-002 invariant being asserted is
        // about anonymous probes specifically — sibling contexts in the JVM may continue to
        // run filters that persist sessions in the background, so a shared SPRING_SESSION
        // table makes the absolute-zero assertion non-deterministic.
        SharedPostgres.registerIsolatedDatabase(registry, AnonymousSessionPersistenceIntegrationTest.class);
    }

    @BeforeEach
    void truncateSpringSession() {
        // Wipe sibling-class sessions so the absolute-zero assertion is meaningful. Order
        // matters: SPRING_SESSION_ATTRIBUTES has an FK back to SPRING_SESSION.
        jdbc.execute("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        jdbc.execute("DELETE FROM SPRING_SESSION");
    }

    @Test
    void anonymousCsrfCalls_doNotPersistSpringSessionRows() throws Exception {
        for (int i = 0; i < 5; i++) {
            // No oauth2Login / no with(csrf()) — purely unauthenticated probes, exactly as
            // the F-002 PoC issued from outside any Discord session.
            mockMvc.perform(MockMvcRequestBuilders.get("/csrf"));
        }

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);
        assertThat(count)
                .as("Anonymous /csrf calls must not persist any SPRING_SESSION rows (F-002)")
                .isZero();
    }
}
