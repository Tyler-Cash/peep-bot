package dev.tylercash.event.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.discord.AvatarDownloadService;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.immich.ImmichService;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared boilerplate for HTTP-level integration tests. Each subclass implicitly gets:
 * <ul>
 *   <li>A SpringBootTest context with the full bean graph (no Discord token needed — JDA +
 *       DiscordService + DiscordInitializationService are Mockito-bean'd so the bot never
 *       starts).</li>
 *   <li>A Testcontainers Postgres database that Liquibase populates on startup.</li>
 *   <li>A clean slate for the most-mutated tables in {@code @BeforeEach}.</li>
 *   <li>{@code MockMvc}, {@code ObjectMapper}, {@code JdbcTemplate}, and the {@link
 *       HttpIntegrationFixtures} helper auto-wired for direct use.</li>
 *   <li>Helper {@link #authedAs(String)} for the very common case of "post a request as a
 *       Discord user with this snowflake".</li>
 * </ul>
 */
@ResourceLock("http-integration-test-db")
@SpringBootTest(
        classes = PeepBotApplication.class,
        properties = {
            "spring.security.oauth2.client.registration.discord.client-id=test",
            "spring.security.oauth2.client.registration.discord.client-secret=test",
            "dev.tylercash.discord.token=dummy",
            "dev.tylercash.discord.guild-id=0",
            // Existing yaml has a single-guild yaml field; keep zero so nothing
            // accidentally targets a real guild from a test.
            "dev.tylercash.rate-limit.read-capacity=10000",
            "dev.tylercash.rate-limit.write-capacity=10000",
        })
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(HttpIntegrationFixtures.class)
public abstract class AbstractHttpIntegrationTest {

    // Started once for the entire JVM session; not annotated @Container so Testcontainers never
    // stops it between test classes.  Ryuk/shutdown-hook cleans it up at JVM exit.
    protected static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean
    protected JDA jda;

    @MockitoBean
    protected DiscordService discordService;

    @MockitoBean
    protected DiscordInitializationService discordInitializationService;

    @MockitoBean
    protected ImmichService immichService;

    @MockitoBean
    protected AvatarDownloadService avatarDownloadService;

    @MockitoBean
    protected EmbeddingModel embeddingModel;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected HttpIntegrationFixtures fixtures;

    @BeforeEach
    void truncate() {
        jdbc.execute("DELETE FROM attendance");
        jdbc.execute("DELETE FROM event_category");
        jdbc.execute("DELETE FROM event_classification_attempt");
        jdbc.execute("DELETE FROM event");
        jdbc.execute("DELETE FROM discord_user_guild");
        jdbc.execute("DELETE FROM discord_user_cache");
    }

    /** Authenticate the request as the given Discord snowflake. */
    protected RequestPostProcessor authedAs(String snowflake) {
        return SecurityMockMvcRequestPostProcessors.oauth2Login().attributes(a -> a.put("id", snowflake));
    }
}
