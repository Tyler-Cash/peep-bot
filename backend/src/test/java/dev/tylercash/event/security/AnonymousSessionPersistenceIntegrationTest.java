package dev.tylercash.event.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pentest finding F-002: anonymous calls used to provision a fresh
 * {@code SPRING_SESSION} row per request, with the global multi-day TTL.
 * Five unauthenticated {@code GET /csrf} calls must leave the table empty —
 * the CSRF cookie itself is independent of session persistence and is the
 * only deliverable of that endpoint.
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
@Testcontainers
@ActiveProfiles("local")
class AnonymousSessionPersistenceIntegrationTest {

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void truncateSpringSession() {
        jdbc.execute("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        jdbc.execute("DELETE FROM SPRING_SESSION");
    }

    @Test
    void anonymousCsrfCalls_doNotPersistSpringSessionRows() throws Exception {
        // Five unauthenticated probes — the CSRF filter still runs and may set the XSRF-TOKEN
        // cookie, but the SESSION row backing each request must not be flushed to the DB.
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
