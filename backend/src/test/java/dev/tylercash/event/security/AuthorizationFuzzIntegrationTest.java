package dev.tylercash.event.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.event.AttendanceService;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Authorization fuzz suite — for every controller endpoint that requires auth,
 * assert anonymous → 401, wrong-guild → 403 (when the endpoint is guild-scoped),
 * and non-admin → 403 on admin-only endpoints. Endpoint cases are enumerated
 * explicitly because most paths take typed path variables and meaningful bodies
 * that can't be safely faked. A separate {@link #everyControllerHasAtLeastOneCase}
 * test fails when a new {@code @RestController} ships without coverage here.
 */
@SpringBootTest(
        classes = PeepBotApplication.class,
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "spring.security.oauth2.client.registration.discord.client-id=test",
            "spring.security.oauth2.client.registration.discord.client-secret=test",
            "dev.tylercash.discord.token=dummy",
            "dev.tylercash.discord.guild-id=0",
            // Defaults are 60 read / 5 write per minute — way too small for a
            // parameterized test that fans out across every endpoint from a
            // single client IP. Crank them up so RateLimitFilter doesn't mask
            // the real auth/authz responses we are asserting on.
            "dev.tylercash.rate-limit.read-capacity=10000",
            "dev.tylercash.rate-limit.write-capacity=10000"
        })
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
class AuthorizationFuzzIntegrationTest {

    private static final long GUILD_1 = 311L;
    private static final long GUILD_2 = 322L;
    // Snowflake-shaped (numeric) IDs — controllers parse these via Long.parseLong.
    private static final String USER_IN_GUILD_1 = "311111";
    private static final String USER_IN_GUILD_2 = "322222";

    /**
     * Endpoints that are intentionally public — keep this allowlist as small as
     * possible, and document why each entry is here.
     */
    private static final Set<String> PUBLIC_ENDPOINT_ALLOWLIST = Set.of(
            // Used by the unauthenticated frontend to decide whether to render
            // the login page; explicitly permitted in WebSecurityConfig.
            "GET /auth/is-logged-in",
            // CSRF token bootstrap — the frontend must be able to fetch a token
            // before it can authenticate or perform any mutation.
            "GET /csrf",
            // Spring Security framework endpoints — not @RestControllers but
            // listed here for clarity.
            "POST /auth/logout",
            // Swagger / actuator — explicitly permitAll in WebSecurityConfig.
            "GET /swagger-ui.html",
            "GET /v3/api-docs",
            "GET /v3/api-docs.yaml",
            "GET /v3/api-docs/swagger-config",
            "GET /actuator/health",
            "GET /actuator/prometheus");

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
    private ApplicationContext context;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private DiscordUserCacheService discordUserCacheService;

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private dev.tylercash.event.db.repository.EventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private static UUID guild1EventId;

    private static final AtomicLong messageIdCounter = new AtomicLong(20_000);

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
        jdbc.execute("DELETE FROM discord_guild_member");
        jdbc.execute("DELETE FROM discord_user_cache");

        discordUserCacheService.registerIfMissing(USER_IN_GUILD_1, "Fuzzy A", "fuzzya", GUILD_1);
        discordUserCacheService.registerIfMissing(USER_IN_GUILD_2, "Fuzzy B", "fuzzyb", GUILD_2);

        long id = messageIdCounter.incrementAndGet();
        Event event = new Event(
                id,
                GUILD_1,
                id,
                "fuzz-event",
                USER_IN_GUILD_1,
                ZonedDateTime.now().plusDays(1),
                "desc");
        event.setState(EventState.PLANNED);
        guild1EventId = eventRepository.save(event).getId();
        attendanceService.recordAttendance(guild1EventId, USER_IN_GUILD_1, null, AttendanceStatus.ACCEPTED, null);
    }

    /**
     * Every protected endpoint must reject unauthenticated requests with 401.
     * We hit the wire through the real security filter chain via MockMvc.
     */
    @ParameterizedTest(name = "anonymous {0} → 401")
    @MethodSource("protectedEndpoints")
    void anonymous_isUnauthorized(EndpointCase ec) throws Exception {
        mockMvc.perform(withCsrfIfMutating(ec))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("Anonymous %s should be 401", ec)
                        .isEqualTo(401));
    }

    /**
     * For guild-scoped endpoints, a user authenticated but not a member of the
     * target guild must get 403. The status is produced by
     * {@link dev.tylercash.event.discord.GuildMembershipService#assertMember}.
     */
    @ParameterizedTest(name = "wrong-guild user on {0} → 403")
    @MethodSource("guildScopedEndpoints")
    void wrongGuildUser_isForbidden(EndpointCase ec) throws Exception {
        mockMvc.perform(withCsrfIfMutating(ec).with(oauth2Login().attributes(a -> a.put("id", USER_IN_GUILD_2))))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("Endpoint %s should reject cross-guild access", ec)
                        .isEqualTo(403));
    }

    /**
     * Admin-only mutating endpoints must 403 a non-admin (but otherwise valid)
     * member of the same guild.
     */
    @ParameterizedTest(name = "non-admin {0} → 403")
    @MethodSource("adminOnlyEndpoints")
    void nonAdmin_isForbidden(EndpointCase ec) throws Exception {
        // discordService is a @MockitoBean, so isUserAdminOfServer defaults to
        // false — exactly what we want for "not an admin".
        mockMvc.perform(withCsrfIfMutating(ec).with(oauth2Login().attributes(a -> a.put("id", USER_IN_GUILD_1))))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("Endpoint %s should reject non-admin", ec)
                        .isEqualTo(403));
    }

    private static MockHttpServletRequestBuilder withCsrfIfMutating(EndpointCase ec) {
        MockHttpServletRequestBuilder b = ec.builder();
        if (!"GET".equalsIgnoreCase(ec.method())) {
            b = b.with(csrf());
        }
        return b;
    }

    /**
     * Reflective tripwire: enumerate every {@code @RestController} bean's
     * mapped methods and assert that, modulo the allowlist, each one has at
     * least one case in {@link #protectedEndpoints()}. This is what makes the
     * suite catch newly-added controllers.
     */
    @Test
    void everyControllerEndpointHasACoverageEntry() {
        Set<String> covered = new HashSet<>();
        protectedEndpoints().forEach(ec -> covered.add(ec.method() + " " + ec.urlTemplate()));

        List<String> missing = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
                handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod hm = entry.getValue();
            Class<?> beanType = hm.getBeanType();
            if (!beanType.isAnnotationPresent(RestController.class)) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            Set<String> patterns = info.getPathPatternsCondition() != null
                    ? info.getPathPatternsCondition().getPatternValues()
                    : info.getPatternsCondition().getPatterns();
            Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                    info.getMethodsCondition().getMethods();
            // If no HTTP methods are explicitly declared, Spring matches all.
            // For our allowlist match we expand to GET (the only one we currently
            // care about).
            Collection<org.springframework.web.bind.annotation.RequestMethod> effective =
                    methods.isEmpty() ? List.of(org.springframework.web.bind.annotation.RequestMethod.GET) : methods;
            for (String pattern : patterns) {
                for (org.springframework.web.bind.annotation.RequestMethod m : effective) {
                    String key = m.name() + " " + pattern;
                    if (PUBLIC_ENDPOINT_ALLOWLIST.contains(key)) continue;
                    if (covered.contains(key)) continue;
                    // Unknown endpoint — no coverage.
                    missing.add(key + "  (" + beanType.getSimpleName() + "#"
                            + hm.getMethod().getName() + ")");
                }
            }
        }
        assertThat(missing)
                .as(
                        "New @RestController endpoints have appeared without an entry in the auth fuzz suite. "
                                + "Either add an EndpointCase covering it, or — only if it's truly public — add it to PUBLIC_ENDPOINT_ALLOWLIST.")
                .isEmpty();
    }

    /**
     * Sanity check on the reflective scan itself: confirm the @RestController
     * count we discover matches what's actually on the classpath.
     */
    @Test
    void controllerScanIsNonEmpty() {
        long restControllerBeans = Stream.of(context.getBeanNamesForAnnotation(RestController.class))
                .map(context::getType)
                .filter(t -> t != null && t.isAnnotationPresent(RequestMapping.class)
                        || t != null && t.isAnnotationPresent(RestController.class))
                .count();
        assertThat(restControllerBeans).isGreaterThanOrEqualTo(8);
    }

    // -------------------- endpoint case enumeration --------------------

    /**
     * Enumerated coverage list. To add a new endpoint, append a row here.
     * {@code urlTemplate} must match Spring's path pattern verbatim (so the
     * tripwire matches it).
     */
    static Stream<EndpointCase> protectedEndpoints() {
        return Stream.of(
                // RewindController
                new EndpointCase(
                        "GET", "/rewind", () -> MockMvcRequestBuilders.get("/rewind?guildId=" + GUILD_1), true, false),
                new EndpointCase(
                        "GET",
                        "/rewind/me",
                        () -> MockMvcRequestBuilders.get("/rewind/me?guildId=" + GUILD_1),
                        true,
                        false),
                new EndpointCase(
                        "GET",
                        "/rewind/years",
                        () -> MockMvcRequestBuilders.get("/rewind/years?guildId=" + GUILD_1),
                        true,
                        false),

                // GuildController — not guild-scoped (lists all of the user's guilds)
                new EndpointCase("GET", "/guild", () -> MockMvcRequestBuilders.get("/guild"), false, false),

                // GuildSettingsController
                new EndpointCase(
                        "GET",
                        "/guild/{guildId}/settings",
                        () -> MockMvcRequestBuilders.get("/guild/" + GUILD_1 + "/settings"),
                        true,
                        false),
                new EndpointCase(
                        "PATCH",
                        "/guild/{guildId}/settings",
                        () -> MockMvcRequestBuilders.patch("/guild/" + GUILD_1 + "/settings")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"primaryLocationPlaceId\":null,\"primaryLocationName\":null,\"primaryLocationLat\":null,\"primaryLocationLng\":null}"),
                        true,
                        true),

                // SecurityController is in the public allowlist — not in this list.

                // EventController
                new EndpointCase(
                        "GET", "/event", () -> MockMvcRequestBuilders.get("/event?guildId=" + GUILD_1), true, false),
                new EndpointCase(
                        "GET", "/event/{id}", () -> MockMvcRequestBuilders.get("/event/" + guild1EventId), true, false),
                new EndpointCase(
                        "PUT",
                        "/event",
                        () -> MockMvcRequestBuilders.put("/event")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"guildId\":" + GUILD_1
                                        + ",\"name\":\"never created\",\"dateTime\":\"2099-01-01T00:00:00Z\"}"),
                        true,
                        false),
                new EndpointCase(
                        "PATCH",
                        "/event",
                        () -> MockMvcRequestBuilders.patch("/event")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"id\":\"" + guild1EventId + "\"}"),
                        // Wrong-guild check is done via the event's own serverId
                        // (loaded from DB), so userInGuild2 trying to update
                        // guild1EventId hits assertMember → 403.
                        true,
                        false),
                new EndpointCase(
                        "POST",
                        "/event/{id}/rsvp",
                        () -> MockMvcRequestBuilders.post("/event/" + guild1EventId + "/rsvp")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"going\"}"),
                        true,
                        false),
                new EndpointCase(
                        "POST",
                        "/event/{id}/cancel",
                        () -> MockMvcRequestBuilders.post("/event/" + guild1EventId + "/cancel"),
                        // Cancel checks isUserAdminOfServer, which uses
                        // guild_id but does not first call assertMember, so
                        // wrong-guild is rejected the same way as non-admin (403).
                        true,
                        true),
                new EndpointCase(
                        "POST",
                        "/event/{id}/private-channel",
                        () -> MockMvcRequestBuilders.post("/event/" + guild1EventId + "/private-channel"),
                        true,
                        true),
                new EndpointCase(
                        "DELETE",
                        "/event/{id}/attendee",
                        () -> MockMvcRequestBuilders.delete(
                                "/event/" + guild1EventId + "/attendee?snowflake=" + USER_IN_GUILD_1),
                        true,
                        true),
                new EndpointCase(
                        "POST",
                        "/event/{id}/recategorize",
                        () -> MockMvcRequestBuilders.post("/event/" + guild1EventId + "/recategorize"),
                        true,
                        true),

                // GalleryController
                new EndpointCase(
                        "GET",
                        "/gallery",
                        () -> MockMvcRequestBuilders.get("/gallery?guildId=" + GUILD_1),
                        true,
                        false),
                // The thumbnail/open endpoints take an opaque albumId and gate
                // by event membership, not guild — they 404 a wrong-guild user
                // (which is itself fine: no leakage), so we don't fuzz them
                // with the guild-mismatch check. We still cover anonymous→401.
                new EndpointCase(
                        "GET",
                        "/gallery/thumbnail/{albumId}",
                        () -> MockMvcRequestBuilders.get("/gallery/thumbnail/album-xyz"),
                        false,
                        false),
                new EndpointCase(
                        "GET",
                        "/gallery/{albumId}/open",
                        () -> MockMvcRequestBuilders.get("/gallery/album-xyz/open"),
                        false,
                        false),

                // AvatarController — gated by haveSharedGuild, not the
                // standard guild membership service.
                new EndpointCase(
                        "GET",
                        "/avatar/{snowflake}",
                        () -> MockMvcRequestBuilders.get("/avatar/" + USER_IN_GUILD_1),
                        false,
                        false));
    }

    static Stream<EndpointCase> guildScopedEndpoints() {
        return protectedEndpoints().filter(EndpointCase::guildScoped);
    }

    static Stream<EndpointCase> adminOnlyEndpoints() {
        return protectedEndpoints().filter(EndpointCase::adminOnly);
    }

    /**
     * @param method        HTTP method (uppercase) — used by the tripwire match.
     * @param urlTemplate   Spring path pattern (with {placeholders}) — used by
     *                       the tripwire match.
     * @param builder       supplier that materialises a real MockMvc request.
     * @param guildScoped   true if the endpoint should 403 cross-guild access.
     * @param adminOnly     true if the endpoint should 403 a non-admin.
     */
    record EndpointCase(
            String method,
            String urlTemplate,
            java.util.function.Supplier<MockHttpServletRequestBuilder> builderSupplier,
            boolean guildScoped,
            boolean adminOnly) {
        MockHttpServletRequestBuilder builder() {
            return builderSupplier.get();
        }

        @Override
        public String toString() {
            return method + " " + urlTemplate;
        }
    }
}
