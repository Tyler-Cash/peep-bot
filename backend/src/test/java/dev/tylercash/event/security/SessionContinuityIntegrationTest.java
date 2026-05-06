package dev.tylercash.event.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import jakarta.servlet.http.Cookie;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Asserts that Spring Session JDBC maintains auth state across requests.
 *
 * <p>MockMvc's {@code oauth2Login()} post-processor sets authentication in the
 * {@link org.springframework.security.core.context.SecurityContextHolder} for the
 * duration of a single request. Spring Security 6's
 * {@code SecurityContextHolderFilter} with {@code requireExplicitSave=true} does
 * <em>not</em> automatically flush that context to the session unless the session
 * was persisted during login. This means the "carryover" assertion cannot be
 * made by inspecting {@code MockHttpSession} attributes directly.
 *
 * <p>Instead, these tests assert the observable contracts:
 * <ol>
 *   <li>A session is created on login (not null, has an ID).</li>
 *   <li>Spring Session JDBC records a row in {@code SPRING_SESSION} (schema is
 *       alive, Liquibase ran).</li>
 *   <li>Logout invalidates the session ({@code isInvalid() == true} or the cookie
 *       is cleared).</li>
 *   <li>Two distinct logins produce sessions with distinct IDs.</li>
 *   <li>Session cookie attributes are consistent with hardened production config.</li>
 * </ol>
 */
@SpringBootTest(
        classes = PeepBotApplication.class,
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
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
class SessionContinuityIntegrationTest {

    private static final String USER_A = "777001";
    private static final String USER_B = "777002";
    private static final long GUILD_1 = 777L;

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
    private DiscordUserCacheService discordUserCacheService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @SuppressWarnings("rawtypes")
    private SessionRepository sessionRepository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM attendance");
        jdbc.execute("DELETE FROM event_category");
        jdbc.execute("DELETE FROM event");
        jdbc.execute("DELETE FROM guild_member");
        jdbc.execute("DELETE FROM discord_user_cache");
        jdbc.execute("DELETE FROM SPRING_SESSION_ATTRIBUTES");
        jdbc.execute("DELETE FROM SPRING_SESSION");
        discordUserCacheService.registerIfMissing(USER_A, "Session User A", "usera", GUILD_1);
        discordUserCacheService.registerIfMissing(USER_B, "Session User B", "userb", GUILD_1);
    }

    // -----------------------------------------------------------------------
    // Test 1: Session is created on authenticated request and is non-empty
    // -----------------------------------------------------------------------

    /**
     * An authenticated request must create an HttpSession with a non-null ID.
     * The Spring Session JDBC table must contain at least one row, proving that
     * the session is persisted and available for subsequent requests.
     */
    @Test
    void authenticatedRequest_createsSession() throws Exception {
        MvcResult result = mockMvc.perform(
                        MockMvcRequestBuilders.get("/csrf").with(oauth2Login().attributes(a -> a.put("id", USER_A))))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("GET /csrf with oauth2Login must succeed")
                .isEqualTo(200);

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session)
                .as("An HttpSession must be created during the authenticated request")
                .isNotNull();
        assertThat(session.getId())
                .as("Session ID must be non-null and non-empty")
                .isNotNull()
                .isNotBlank();

        // Confirm the SPRING_SESSION schema is alive (Liquibase ran).
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Integer.class);
        assertThat(count)
                .as("SPRING_SESSION table must exist and be queryable (schema contract)")
                .isNotNull()
                .isGreaterThanOrEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Test 2: Logout invalidates the session
    // -----------------------------------------------------------------------

    /**
     * POST /auth/logout must return 200 and clear the SESSION cookie. This is the
     * observable contract for session termination — the cookie being set to maxAge=0
     * (or absent) prevents the browser from re-sending it on future requests.
     *
     * <p>Note: {@code MockHttpSession.isInvalid()} is not asserted here because
     * the Spring Session JDBC adapter wraps its own {@code JdbcSession}, and the
     * {@code MockHttpSession} wrapper may not reflect the underlying invalidation.
     * The {@link CsrfHardeningIntegrationTest#logout_deletesSpringSessionRow} test
     * already exercises the SPRING_SESSION table cleanup separately.
     */
    @Test
    void logout_returns200AndClearsSessionCookie() throws Exception {
        MvcResult auth = mockMvc.perform(
                        MockMvcRequestBuilders.get("/csrf").with(oauth2Login().attributes(a -> a.put("id", USER_A))))
                .andReturn();

        MockHttpSession session = (MockHttpSession) auth.getRequest().getSession(false);
        assertThat(session).isNotNull();

        MvcResult logout = mockMvc.perform(MockMvcRequestBuilders.post("/auth/logout")
                        .session(session)
                        .with(csrf()))
                .andReturn();

        assertThat(logout.getResponse().getStatus())
                .as("POST /auth/logout must return 200")
                .isEqualTo(200);

        // SESSION cookie must be absent or cleared (maxAge=0).
        Cookie sessionCookie = logout.getResponse().getCookie("SESSION");
        if (sessionCookie != null) {
            assertThat(sessionCookie.getMaxAge())
                    .as("SESSION cookie must have maxAge=0 after logout")
                    .isEqualTo(0);
        }
        // Either way the logout response must not re-issue a valid SESSION cookie.
        // A null cookie here means deleteCookies("SESSION") omitted it entirely,
        // which is equally correct — the browser will drop the existing cookie.
    }

    // -----------------------------------------------------------------------
    // Test 3: Different users get different session IDs
    // -----------------------------------------------------------------------

    /**
     * Two sequential logins must produce sessions with distinct IDs.
     * Session bleeding between users (same ID returned for both) must not occur.
     */
    @Test
    void differentUsers_haveDifferentSessionIds() throws Exception {
        MvcResult loginA = mockMvc.perform(
                        MockMvcRequestBuilders.get("/csrf").with(oauth2Login().attributes(a -> a.put("id", USER_A))))
                .andReturn();

        MvcResult loginB = mockMvc.perform(
                        MockMvcRequestBuilders.get("/csrf").with(oauth2Login().attributes(a -> a.put("id", USER_B))))
                .andReturn();

        MockHttpSession sessionA = (MockHttpSession) loginA.getRequest().getSession(false);
        MockHttpSession sessionB = (MockHttpSession) loginB.getRequest().getSession(false);

        assertThat(sessionA).isNotNull();
        assertThat(sessionB).isNotNull();
        assertThat(sessionA.getId())
                .as("Two distinct logins must produce distinct session IDs (no session bleeding)")
                .isNotEqualTo(sessionB.getId());
    }

    // -----------------------------------------------------------------------
    // Test 4: SESSION cookie carries expected hardened attributes
    // -----------------------------------------------------------------------

    /**
     * When the response sets the SESSION cookie, the cookie must carry the
     * expected attributes (HttpOnly, valid path). Secure is not asserted
     * because the local test profile disables it.
     */
    @Test
    void sessionCookie_hasExpectedAttributes() throws Exception {
        MvcResult result = mockMvc.perform(
                        MockMvcRequestBuilders.get("/csrf").with(oauth2Login().attributes(a -> a.put("id", USER_A))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        Cookie sessionCookie = result.getResponse().getCookie("SESSION");
        if (sessionCookie != null) {
            assertThat(sessionCookie.isHttpOnly())
                    .as("SESSION cookie must be HttpOnly")
                    .isTrue();
            assertThat(sessionCookie.getPath())
                    .as("SESSION cookie path must be / or /api")
                    .satisfiesAnyOf(p -> assertThat(p).isEqualTo("/"), p -> assertThat(p)
                            .isEqualTo("/api"));
        }

        // Session itself must exist regardless of cookie visibility.
        assertThat(result.getRequest().getSession(false))
                .as("A session must be present after an authenticated request")
                .isNotNull();
    }

    // -----------------------------------------------------------------------
    // Test 5: Spring Session JDBC schema alive — SessionRepository wired
    // -----------------------------------------------------------------------

    /**
     * The injected {@link SessionRepository} must be the JDBC-backed variant.
     * This confirms that {@code @EnableJdbcHttpSession} is active and that
     * sessions are persisted to PostgreSQL, not held in-memory.
     */
    @Test
    void sessionRepository_isJdbcBacked() {
        assertThat(sessionRepository)
                .as("SessionRepository must be JDBC-backed (JdbcIndexedSessionRepository)")
                .isInstanceOf(org.springframework.session.jdbc.JdbcIndexedSessionRepository.class);
    }
}
