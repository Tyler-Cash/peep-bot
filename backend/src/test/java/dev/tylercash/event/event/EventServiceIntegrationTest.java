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
    public static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

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
        discordUserCacheService.upsertUser("cache1", "OriginalName");

        assertThat(discordUserCacheService.getDisplayName("cache1")).isEqualTo("OriginalName");

        // Upsert with new name
        discordUserCacheService.upsertUser("cache1", "UpdatedName");
        assertThat(discordUserCacheService.getDisplayName("cache1")).isEqualTo("UpdatedName");
    }

    @Test
    @DisplayName("discord_user_cache batch lookup works against real database")
    void discordUserCache_batchLookup() {
        discordUserCacheService.upsertUser("batch1", "Alice");
        discordUserCacheService.upsertUser("batch2", "Bob");

        Map<String, String> names = discordUserCacheService.getDisplayNames(List.of("batch1", "batch2", "missing"));

        assertThat(names).hasSize(2).containsEntry("batch1", "Alice").containsEntry("batch2", "Bob");
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
