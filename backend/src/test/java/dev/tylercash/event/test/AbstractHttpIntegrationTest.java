package dev.tylercash.event.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.discord.AvatarDownloadService;
import dev.tylercash.event.discord.DiscordAuthService;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.immich.ImmichService;
import net.dv8tion.jda.api.JDA;
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

/**
 * Shared boilerplate for HTTP-level integration tests. Each subclass implicitly gets:
 * <ul>
 *   <li>A SpringBootTest context with the full bean graph (no Discord token needed — JDA +
 *       DiscordService + DiscordInitializationService are Mockito-bean'd so the bot never
 *       starts).</li>
 *   <li>A shared Postgres container ({@link SharedPostgres}). Liquibase populates the schema
 *       on first context startup; subsequent classes reuse it.</li>
 *   <li>{@code MockMvc}, {@code ObjectMapper}, {@code JdbcTemplate}, and the {@link
 *       HttpIntegrationFixtures} helper auto-wired for direct use.</li>
 *   <li>Helper {@link #authedAs(String)} for the very common case of "post a request as a
 *       Discord user with this snowflake".</li>
 * </ul>
 *
 * <p><b>Test isolation:</b> there is no global truncate. Each test must own the data it
 * touches — use unique guild IDs / event names / snowflakes per test, and assert on those
 * specific rows (never "find all" or "size == 1"). Tests sharing a database is what lets the
 * suite run in parallel.
 */
@SpringBootTest(
        classes = PeepBotApplication.class,
        properties = {
            "spring.security.oauth2.client.registration.discord.client-id=test",
            "spring.security.oauth2.client.registration.discord.client-secret=test",
            "dev.tylercash.discord.token=dummy",
            "dev.tylercash.discord.guild-id=0",
            "dev.tylercash.frontend.hostname=test.local",
            // Existing yaml has a single-guild yaml field; keep zero so nothing
            // accidentally targets a real guild from a test.
            "dev.tylercash.rate-limit.read-capacity=10000",
            "dev.tylercash.rate-limit.write-capacity=10000",
        })
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(HttpIntegrationFixtures.class)
public abstract class AbstractHttpIntegrationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        SharedPostgres.registerProperties(r);
    }

    @MockitoBean
    protected JDA jda;

    @MockitoBean
    protected DiscordService discordService;

    @MockitoBean
    protected DiscordAuthService discordAuthService;

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

    /** Authenticate the request as the given Discord snowflake. */
    protected RequestPostProcessor authedAs(String snowflake) {
        return SecurityMockMvcRequestPostProcessors.oauth2Login().attributes(a -> a.put("id", snowflake));
    }
}
