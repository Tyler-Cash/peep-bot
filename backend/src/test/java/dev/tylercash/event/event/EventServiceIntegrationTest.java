package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.event.model.*;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
class EventServiceIntegrationTest {

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @Container
    public static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");

    @Autowired
    private EventService eventService;

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private AttendanceRepository attendanceRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DiscordUserCacheService discordUserCacheService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        TextChannel mockChannel = mock(TextChannel.class);
        Message mockMessage = mock(Message.class);
        when(mockChannel.getIdLong()).thenReturn(100L);
        when(mockMessage.getGuildIdLong()).thenReturn(200L);
        when(mockMessage.getIdLong()).thenReturn(300L);
        when(discordService.createEventChannel(any())).thenReturn(mockChannel);
        when(discordService.postEventMessage(any(), any())).thenReturn(mockMessage);
    }

    @Test
    public void testSave() {
        Event event = new Event(0, 0, 0, "name", "creator", ZonedDateTime.now(), "description");
        eventService.createEvent(event);
    }

    private static final AtomicLong messageIdCounter = new AtomicLong(1000);

    private UUID createPersistedEvent() {
        long uniqueId = messageIdCounter.incrementAndGet();
        Event event = new Event(
                uniqueId,
                0,
                uniqueId,
                "test-event",
                "creator",
                ZonedDateTime.now().plusDays(1),
                "desc");
        event.setState(EventState.PLANNED);
        return eventRepository.save(event).getId();
    }

    @Test
    @DisplayName("attendance table exists and supports insert + DISTINCT ON query")
    void attendanceTable_insertAndQuery() {
        UUID eventId = createPersistedEvent();

        attendanceService.recordAttendance(eventId, "user1", null, AttendanceStatus.ACCEPTED, null);
        attendanceService.recordAttendance(eventId, "user1", null, AttendanceStatus.DECLINED, null);

        List<AttendanceRecord> latest = attendanceRepository.findLatestPerAttendee(eventId);

        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).getSnowflake()).isEqualTo("user1");
        assertThat(latest.get(0).getStatus()).isEqualTo(AttendanceStatus.DECLINED);
    }

    @Test
    @DisplayName("DISTINCT ON returns latest record per attendee across snowflake and name-only attendees")
    void attendanceTable_distinctOnCoalesces() {
        UUID eventId = createPersistedEvent();

        // Discord user changes status twice
        attendanceService.recordAttendance(eventId, "discord1", null, AttendanceStatus.ACCEPTED, null);
        attendanceService.recordAttendance(eventId, "discord1", null, AttendanceStatus.MAYBE, null);

        // +1 guest added then removed
        attendanceService.recordAttendance(eventId, null, "[+1] Guest", AttendanceStatus.ACCEPTED, "discord1");
        attendanceService.recordAttendance(eventId, null, "[+1] Guest", AttendanceStatus.REMOVED, "discord1");

        // Another Discord user stays accepted
        attendanceService.recordAttendance(eventId, "discord2", null, AttendanceStatus.ACCEPTED, null);

        List<AttendanceRecord> latest = attendanceRepository.findLatestPerAttendee(eventId);

        assertThat(latest).hasSize(3);

        AttendanceRecord discord1 = latest.stream()
                .filter(r -> "discord1".equals(r.getSnowflake()))
                .findFirst()
                .orElseThrow();
        assertThat(discord1.getStatus()).isEqualTo(AttendanceStatus.MAYBE);

        AttendanceRecord guest = latest.stream()
                .filter(r -> "[+1] Guest".equals(r.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(guest.getStatus()).isEqualTo(AttendanceStatus.REMOVED);

        AttendanceRecord discord2 = latest.stream()
                .filter(r -> "discord2".equals(r.getSnowflake()))
                .findFirst()
                .orElseThrow();
        assertThat(discord2.getStatus()).isEqualTo(AttendanceStatus.ACCEPTED);
    }

    @Test
    @DisplayName("getCurrentAttendance partitions correctly from real database")
    void getCurrentAttendance_fromDatabase() {
        UUID eventId = createPersistedEvent();

        attendanceService.recordAttendance(eventId, "a1", null, AttendanceStatus.ACCEPTED, null);
        attendanceService.recordAttendance(eventId, "d1", null, AttendanceStatus.DECLINED, null);
        attendanceService.recordAttendance(eventId, "m1", null, AttendanceStatus.MAYBE, null);
        attendanceService.recordAttendance(eventId, "r1", null, AttendanceStatus.ACCEPTED, null);
        attendanceService.recordAttendance(eventId, "r1", null, AttendanceStatus.REMOVED, null);

        AttendanceSummary summary = attendanceService.getCurrentAttendance(eventId);

        assertThat(summary.accepted()).hasSize(1);
        assertThat(summary.declined()).hasSize(1);
        assertThat(summary.maybe()).hasSize(1);
    }

    @Test
    @DisplayName("discord_user_cache table exists and supports upsert + lookup")
    void discordUserCache_upsertAndLookup() {
        discordUserCacheService.upsertUser("cache1", "OriginalName", "user1", null, 0L);

        assertThat(discordUserCacheService.getDisplayName(0L, "cache1")).isEqualTo("OriginalName");

        // Upsert with new name
        discordUserCacheService.upsertUser("cache1", "UpdatedName", "user1", null, 0L);
        assertThat(discordUserCacheService.getDisplayName(0L, "cache1")).isEqualTo("UpdatedName");
    }

    @Test
    @DisplayName("discord_user_cache batch lookup works against real database")
    void discordUserCache_batchLookup() {
        discordUserCacheService.upsertUser("batch1", "Alice", "alice_user", null, 0L);
        discordUserCacheService.upsertUser("batch2", "Bob", "bob_user", null, 0L);

        Map<String, String> names = discordUserCacheService.getDisplayNames(0L, List.of("batch1", "batch2", "missing"));

        assertThat(names).hasSize(2).containsEntry("batch1", "Alice").containsEntry("batch2", "Bob");
    }

    @Test
    @DisplayName("flipAttendance toggles to REMOVED when the user clicks their current status again")
    void flipAttendance_togglesOffWhenStatusMatchesCurrent() {
        UUID eventId = createPersistedEvent();
        attendanceService.recordAttendance(eventId, "user1", null, AttendanceStatus.ACCEPTED, null);

        AttendanceStatus result = attendanceService.flipAttendance(eventId, "user1", null, AttendanceStatus.ACCEPTED);

        assertThat(result).isEqualTo(AttendanceStatus.REMOVED);
        List<AttendanceRecord> latest = attendanceRepository.findLatestPerAttendee(eventId);
        assertThat(latest)
                .filteredOn(r -> "user1".equals(r.getSnowflake()))
                .singleElement()
                .extracting(AttendanceRecord::getStatus)
                .isEqualTo(AttendanceStatus.REMOVED);
    }

    @Test
    @DisplayName("flipAttendance switches between statuses without toggling off")
    void flipAttendance_switchesBetweenStatuses() {
        UUID eventId = createPersistedEvent();
        attendanceService.recordAttendance(eventId, "user1", null, AttendanceStatus.MAYBE, null);

        AttendanceStatus result = attendanceService.flipAttendance(eventId, "user1", null, AttendanceStatus.ACCEPTED);

        assertThat(result).isEqualTo(AttendanceStatus.ACCEPTED);
    }

    @Test
    @DisplayName("flipAttendance cascades REMOVED to +1 guests owned by the user when they decline")
    void flipAttendance_cascadeRemovesOwnedPlusOnesOnDecline() {
        UUID eventId = createPersistedEvent();
        attendanceService.recordAttendance(eventId, "owner", null, AttendanceStatus.ACCEPTED, null);
        attendanceService.recordAttendance(eventId, null, "[+1] Guest A", AttendanceStatus.ACCEPTED, "owner");
        attendanceService.recordAttendance(eventId, null, "[+1] Guest B", AttendanceStatus.ACCEPTED, "owner");

        attendanceService.flipAttendance(eventId, "owner", null, AttendanceStatus.DECLINED);

        AttendanceSummary summary = attendanceService.getCurrentAttendance(eventId);
        assertThat(summary.accepted()).isEmpty();
        assertThat(summary.declined())
                .singleElement()
                .extracting(AttendanceRecord::getSnowflake)
                .isEqualTo("owner");
    }

    @Test
    @DisplayName("concurrent flipAttendance calls for different users each resolve to their requested status")
    void concurrentFlipAttendance_independentUsers() throws Exception {
        UUID eventId = createPersistedEvent();
        int userCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(userCount);
        try {
            for (int i = 0; i < userCount; i++) {
                String snowflake = "u" + i;
                AttendanceStatus requested = i % 3 == 0
                        ? AttendanceStatus.ACCEPTED
                        : (i % 3 == 1 ? AttendanceStatus.DECLINED : AttendanceStatus.MAYBE);
                executor.submit(() -> {
                    try {
                        start.await();
                        attendanceService.flipAttendance(eventId, snowflake, null, requested);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        AttendanceSummary summary = attendanceService.getCurrentAttendance(eventId);
        int totalRecorded = summary.accepted().size()
                + summary.declined().size()
                + summary.maybe().size();
        assertThat(totalRecorded).isEqualTo(userCount);
    }

    @Test
    @DisplayName("findAllDistinctSnowflakes returns unique snowflakes from attendance")
    void findAllDistinctSnowflakes() {
        UUID eventId = createPersistedEvent();
        attendanceService.recordAttendance(eventId, "s1", null, AttendanceStatus.ACCEPTED, null);
        attendanceService.recordAttendance(eventId, "s1", null, AttendanceStatus.DECLINED, null);
        attendanceService.recordAttendance(eventId, "s2", null, AttendanceStatus.ACCEPTED, null);
        attendanceService.recordAttendance(eventId, null, "[+1] Guest", AttendanceStatus.ACCEPTED, "s1");

        List<String> snowflakes = attendanceRepository.findAllDistinctSnowflakes();

        assertThat(snowflakes).contains("s1", "s2").doesNotContainNull();
    }
}
