package dev.tylercash.event.rewind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.event.AttendanceService;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.rewind.model.RewindStatsDto;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies that the rewind feature scopes data by Discord guild membership: a
 * user who only belongs to guild2 cannot read rewind data for guild1, and the
 * data they can see for their own guild does not leak users or events from
 * guild1.
 */
@SpringBootTest(
        classes = PeepBotApplication.class,
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "spring.security.oauth2.client.registration.discord.client-id=test",
            "spring.security.oauth2.client.registration.discord.client-secret=test",
            "dev.tylercash.discord.token=dummy",
            "dev.tylercash.discord.guild-id=0"
        })
@Testcontainers
@ActiveProfiles("local")
class RewindRbacIntegrationTest {

    private static final long GUILD_1 = 111L;
    private static final long GUILD_2 = 222L;

    private static final String USER_A = "userA";
    private static final String USER_B = "userB";
    private static final String USER_C = "userC";
    private static final String USER_D = "userD";

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");

    @Autowired
    private RewindController rewindController;

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DiscordUserCacheService discordUserCacheService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final AtomicLong messageIdCounter = new AtomicLong(10_000);

    @BeforeEach
    void seed() {
        // Reset across methods: tests share the Testcontainers DB and the
        // rewind cache, so without this both compound between methods.
        jdbc.execute("DELETE FROM attendance");
        jdbc.execute("DELETE FROM event_category");
        jdbc.execute("DELETE FROM event");
        jdbc.execute("DELETE FROM discord_user_guild");
        jdbc.execute("DELETE FROM discord_user_cache");
        var rewindCache = cacheManager.getCache("rewind");
        if (rewindCache != null) rewindCache.clear();

        // userA, userB, userC are members of guild1.
        discordUserCacheService.registerIfMissing(USER_A, "Alice", "alice", GUILD_1);
        discordUserCacheService.registerIfMissing(USER_B, "Bob", "bob", GUILD_1);
        discordUserCacheService.registerIfMissing(USER_C, "Carol", "carol", GUILD_1);

        // userD is a member of guild2 only — and is in guild2 alongside no one
        // else from guild1, to avoid any cross-guild collisions.
        discordUserCacheService.registerIfMissing(USER_D, "Dave", "dave", GUILD_2);

        // Two events in guild1 with all three members accepted, so the social
        // graph for guild1 has three connected nodes.
        UUID e1 = createEvent(GUILD_1, "guild1-event-1", USER_A);
        UUID e2 = createEvent(GUILD_1, "guild1-event-2", USER_A);
        for (UUID eventId : new UUID[] {e1, e2}) {
            attendanceService.recordAttendance(eventId, USER_A, null, AttendanceStatus.ACCEPTED, null);
            attendanceService.recordAttendance(eventId, USER_B, null, AttendanceStatus.ACCEPTED, null);
            attendanceService.recordAttendance(eventId, USER_C, null, AttendanceStatus.ACCEPTED, null);
        }

        // An event in guild2, created by and attended only by userD.
        UUID e3 = createEvent(GUILD_2, "guild2-event-1", USER_D);
        attendanceService.recordAttendance(e3, USER_D, null, AttendanceStatus.ACCEPTED, null);
    }

    @Test
    @DisplayName("userD (member of guild2 only) cannot read guild1's rewind stats")
    void userD_forbiddenFromGuild1Stats() {
        assertThatThrownBy(() -> rewindController.getGuildStats(principal(USER_D), GUILD_1, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("userD cannot read guild1's personal rewind even with their own snowflake")
    void userD_forbiddenFromGuild1Personal() {
        assertThatThrownBy(() -> rewindController.getMyStats(principal(USER_D), GUILD_1, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("userD cannot read guild1's available rewind years")
    void userD_forbiddenFromGuild1Years() {
        assertThatThrownBy(() -> rewindController.getYears(principal(USER_D), GUILD_1))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("userD's view of guild2 does not leak users or events from guild1")
    void userD_guild2View_isolatedFromGuild1() {
        RewindStatsDto stats = rewindController.getGuildStats(principal(USER_D), GUILD_2, null);

        // Only the single guild2 event counts.
        assertThat(stats.totalEvents()).isEqualTo(1);
        assertThat(stats.totalUniqueAttendees()).isEqualTo(1);

        // Top attendees / organisers must not contain anyone from guild1.
        assertThat(stats.topAttendees())
                .extracting("displayName")
                .doesNotContain("Alice", "Bob", "Carol");
        assertThat(stats.topOrganizers())
                .extracting("displayName")
                .doesNotContain("Alice", "Bob", "Carol");

        // Social graph must not contain any guild1 user — userD has no
        // co-attendees in guild2, so the graph is empty.
        assertThat(stats.socialGraph()).isNotNull();
        assertThat(stats.socialGraph().nodes())
                .extracting("snowflake")
                .doesNotContain(USER_A, USER_B, USER_C);
        assertThat(stats.socialGraph().edges()).isEmpty();
    }

    @Test
    @DisplayName("a guild1 member sees all three guild1 attendees in the social graph")
    void guild1Member_seesAllGuild1Members() {
        RewindStatsDto stats = rewindController.getGuildStats(principal(USER_A), GUILD_1, null);

        assertThat(stats.socialGraph()).isNotNull();
        assertThat(stats.socialGraph().nodes())
                .extracting("snowflake")
                .containsExactlyInAnyOrder(USER_A, USER_B, USER_C);
        // userD must not appear in guild1's graph.
        assertThat(stats.socialGraph().nodes())
                .extracting("snowflake")
                .doesNotContain(USER_D);
    }

    private UUID createEvent(long guildId, String name, String creator) {
        long id = messageIdCounter.incrementAndGet();
        Event event = new Event(id, guildId, id, name, creator, ZonedDateTime.now().plusDays(1), "desc");
        event.setState(EventState.PLANNED);
        return eventRepository.save(event).getId();
    }

    private OAuth2User principal(String snowflake) {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("id")).thenReturn(snowflake);
        return principal;
    }
}
