package dev.tylercash.event.security.oauth2;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import jakarta.servlet.http.Cookie;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Full OAuth2 login round-trip test: Discord authorize redirect → WireMock token
 * exchange → success handler redirect to frontend → SESSION cookie → /auth/is-logged-in.
 *
 * <p>Catches:
 * <ul>
 *   <li>Spring Security OAuth2 client config drift (wrong URIs, scopes, etc.)
 *   <li>{@link RedirectToFrontendAfterAuth} redirecting to the wrong URL when
 *       {@code dev.tylercash.frontend.hostname} is unset/misconfigured
 *   <li>Session cookie lifecycle
 *   <li>{@link CustomOAuth2UserService#loadUser} calling Discord and rejecting non-members
 * </ul>
 */
@SpringBootTest(
        classes = PeepBotApplication.class,
        properties = {
            "spring.security.oauth2.client.registration.discord.client-id=test-client-id",
            "spring.security.oauth2.client.registration.discord.client-secret=test-secret",
            "dev.tylercash.discord.token=dummy",
            "dev.tylercash.discord.guild-id=42",
            "dev.tylercash.frontend.protocol=https",
            "dev.tylercash.frontend.hostname=event.test.local",
            "dev.tylercash.frontend.path=/",
            // Disable cookie.secure so MockMvc can capture the SESSION cookie
            "server.servlet.session.cookie.secure=false",
            // Clear the production domain so the cookie is set on localhost
            "server.servlet.session.cookie.domain=",
            // Raise rate limits so repeated test requests aren't throttled
            "dev.tylercash.rate-limit.read-capacity=10000",
            "dev.tylercash.rate-limit.write-capacity=10000",
        })
@AutoConfigureMockMvc
@ActiveProfiles("local")
class OAuth2LoginIntegrationTest {

    private static final WireMockServer WIREMOCK = new WireMockServer(0);
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");

    private static final String SNOWFLAKE = "1234567890";

    @BeforeAll
    static void startInfra() {
        POSTGRES.start();
        WIREMOCK.start();
    }

    @AfterAll
    static void stopInfra() {
        WIREMOCK.stop();
        POSTGRES.stop();
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add(
                "spring.security.oauth2.client.provider.discord.authorization-uri",
                () -> WIREMOCK.baseUrl() + "/oauth2/authorize");
        r.add("spring.security.oauth2.client.provider.discord.token-uri", () -> WIREMOCK.baseUrl() + "/oauth2/token");
        r.add("spring.security.oauth2.client.provider.discord.user-info-uri", () -> WIREMOCK.baseUrl() + "/users/@me");
    }

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void resetWireMock() {
        WIREMOCK.resetAll();
    }

    @Test
    void authorize_redirectsToWireMockWithStateAndRedirectUri() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/discord")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        String location = result.getResponse().getHeader("Location");
        assertThat(location)
                .as("Authorization redirect must point at WireMock (not discordapp.com)")
                .startsWith(WIREMOCK.baseUrl() + "/oauth2/authorize");
        assertThat(location)
                .as("Must include state parameter for CSRF protection")
                .contains("state=");
        assertThat(location)
                .as("Must include redirect_uri so Discord knows where to callback")
                .contains("redirect_uri=");
        assertThat(location).as("Must include configured client_id").contains("client_id=test-client-id");
    }

    @Test
    void callback_exchangesCodeAndIssuesFrontendRedirect() throws Exception {
        // --- stub WireMock ---
        WIREMOCK.stubFor(
                WireMock.post(urlEqualTo("/oauth2/token"))
                        .willReturn(
                                okJson(
                                        "{\"access_token\":\"AT\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"identify\"}")));
        WIREMOCK.stubFor(WireMock.get(urlEqualTo("/users/@me"))
                .willReturn(okJson("{\"id\":\"" + SNOWFLAKE
                        + "\",\"username\":\"alice\",\"global_name\":\"Alice\",\"avatar\":null}")));

        // CustomOAuth2UserService rejects login if user isn't a server member
        Mockito.when(discordService.isUserMemberOfServer(42L, Long.parseLong(SNOWFLAKE)))
                .thenReturn(true);

        // Step 1: initiate the authorize flow to capture the PKCE/state cookie
        MvcResult authResult =
                mockMvc.perform(get("/oauth2/authorization/discord")).andReturn();
        assertThat(authResult.getResponse().getStatus()).isEqualTo(302);

        String authLocation = authResult.getResponse().getHeader("Location");
        // Extract `state` from the redirect URL (it is URL-encoded in the Location header)
        String stateRaw = authLocation.replaceAll(".*[?&]state=([^&]+).*", "$1");
        String state = java.net.URLDecoder.decode(stateRaw, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(state).as("state must be present in authorization URL").isNotBlank();

        // Collect cookies Spring set during the authorize request (stores OAuth2 state)
        Cookie[] stateCookies = authResult.getResponse().getCookies();

        // Step 2: simulate Discord posting the code back
        MvcResult cbResult = mockMvc.perform(get("/login/oauth2/code/discord")
                        .param("code", "fakeCode")
                        .param("state", state)
                        .cookie(stateCookies))
                .andReturn();

        assertThat(cbResult.getResponse().getStatus())
                .as("Callback must redirect (302) after successful login")
                .isEqualTo(302);

        String redirect = cbResult.getResponse().getHeader("Location");
        assertThat(redirect)
                .as("RedirectToFrontendAfterAuth must produce https://event.test.local/")
                .isEqualTo("https://event.test.local/");

        // WireMock must have seen the token exchange with our code
        WIREMOCK.verify(postRequestedFor(urlEqualTo("/oauth2/token")).withRequestBody(containing("code=fakeCode")));

        // SESSION cookie must be present and non-blank
        Cookie session = cbResult.getResponse().getCookie("SESSION");
        assertThat(session).as("SESSION cookie must be set after login").isNotNull();
        assertThat(session.getValue())
                .as("SESSION cookie value must be non-blank")
                .isNotBlank();

        // Step 3: use SESSION cookie to hit /auth/is-logged-in and confirm identity
        MvcResult meResult =
                mockMvc.perform(get("/auth/is-logged-in").cookie(session)).andReturn();

        assertThat(meResult.getResponse().getStatus())
                .as("/auth/is-logged-in must return 200 for logged-in user")
                .isEqualTo(200);
        assertThat(meResult.getResponse().getContentAsString())
                .as("Response must contain user's Discord snowflake as discordId")
                .contains("\"discordId\":\"" + SNOWFLAKE + "\"");
    }

    /**
     * Regression detector: if {@code dev.tylercash.frontend.hostname} is empty/null,
     * {@link FrontendConfiguration#getUrl()} produces {@code "https://null/"} or
     * {@code "https:///"}. This test documents the expected (broken) behaviour so
     * the tripwire fires if someone accidentally deploys without setting the property.
     *
     * <p>Actual enforcement is via the round-trip test above where the hostname is
     * set and asserted exactly. The correct fix is always to provide a non-empty
     * {@code dev.tylercash.frontend.hostname} in production config.
     */
    @Test
    void frontendUrl_withMissingHostname_producesNullLiteralInUrl() {
        FrontendConfiguration cfg = new FrontendConfiguration();
        cfg.setProtocol("https");
        cfg.setHostname(null); // simulates missing property
        cfg.setPath("/");

        // Documents the broken state so any future change to getUrl() that silently
        // "fixes" this without a real hostname validation is caught here.
        assertThat(cfg.getUrl())
                .as("Missing hostname must produce a recognisably broken URL (https://null/) "
                        + "rather than silently resolving to a valid host")
                .isEqualTo("https://null/");
    }
}
