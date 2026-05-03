package dev.tylercash.event.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.contract.UserBalanceService;
import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.event.AttendanceService;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests exercising concurrent access to shared mutable state.
 * All tests use a {@link CountDownLatch} start gate so threads fire simultaneously,
 * and {@link CompletableFuture#allOf} to await completion without Thread.sleep.
 */
@SpringBootTest(
        classes = PeepBotApplication.class,
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "spring.security.oauth2.client.registration.discord.client-id=test",
            "spring.security.oauth2.client.registration.discord.client-secret=test",
            "dev.tylercash.discord.token=dummy",
            "dev.tylercash.discord.guild-id=0",
            "dev.tylercash.contract.guild-id=1",
            "dev.tylercash.rate-limit.read-capacity=100000",
            "dev.tylercash.rate-limit.write-capacity=100000"
        })
@Testcontainers
@ActiveProfiles("local")
class ConcurrencyIntegrationTest {

    private static final long GUILD_ID = 500L;
    private static final AtomicLong messageIdCounter = new AtomicLong(50_000);

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private AttendanceRepository attendanceRepository;

    @Autowired
    private UserBalanceService userBalanceService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DiscordUserCacheService discordUserCacheService;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void cleanUp() {
        jdbc.execute("DELETE FROM attendance");
        jdbc.execute("DELETE FROM event_category");
        jdbc.execute("DELETE FROM event");
        jdbc.execute("DELETE FROM discord_user_guild");
        jdbc.execute("DELETE FROM discord_user_cache");
        jdbc.execute("DELETE FROM user_balance");
    }

    // -----------------------------------------------------------------------
    // Test 1: Concurrent RSVPs from distinct users converge to N rows
    // -----------------------------------------------------------------------

    /**
     * Ten threads each call recordAttendance for a distinct snowflake.
     * After completion, the latest-per-attendee view must show exactly 10 rows,
     * all ACCEPTED.
     */
    @Test
    void concurrentRsvpsFromDistinctUsers_producesOneRowPerUser() throws Exception {
        int n = 10;
        UUID eventId = seedEvent("concurrent-distinct");

        // Seed the user cache so JPA foreign-key constraints don't complain
        for (int i = 0; i < n; i++) {
            String snowflake = "user-" + i;
            discordUserCacheService.registerIfMissing(snowflake, "User " + i, "user" + i, GUILD_ID);
        }

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final String snowflake = "user-" + i;
            futures.add(CompletableFuture.runAsync(
                    () -> {
                        try {
                            startGate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        attendanceService.recordAttendance(eventId, snowflake, null, AttendanceStatus.ACCEPTED, null);
                    },
                    pool));
        }

        startGate.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        pool.shutdown();

        var latest = attendanceRepository.findLatestPerAttendee(eventId);
        assertThat(latest)
                .as("Each distinct user must have exactly one current attendance row")
                .hasSize(n);
        assertThat(latest)
                .as("All concurrent RSVPs must be ACCEPTED")
                .allMatch(r -> r.getStatus() == AttendanceStatus.ACCEPTED);
    }

    // -----------------------------------------------------------------------
    // Test 2: Concurrent RSVPs from the same user converge to a single latest
    // -----------------------------------------------------------------------

    /**
     * Five threads all call recordAttendance for snowflake "111" with mixed statuses.
     * The attendance table may accumulate multiple rows (the schema is append-only),
     * but findLatestPerAttendee must return exactly one row for that snowflake.
     */
    @Test
    void concurrentRsvpsFromSameUser_producesSingleLatestEntry() throws Exception {
        int threads = 5;
        UUID eventId = seedEvent("concurrent-same-user");
        discordUserCacheService.registerIfMissing("111", "Same User", "sameuser", GUILD_ID);

        AttendanceStatus[] statuses = {
            AttendanceStatus.ACCEPTED,
            AttendanceStatus.MAYBE,
            AttendanceStatus.DECLINED,
            AttendanceStatus.ACCEPTED,
            AttendanceStatus.MAYBE
        };

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final AttendanceStatus status = statuses[i];
            futures.add(CompletableFuture.runAsync(
                    () -> {
                        try {
                            startGate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        attendanceService.recordAttendance(eventId, "111", null, status, null);
                    },
                    pool));
        }

        startGate.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        pool.shutdown();

        var latest = attendanceRepository.findLatestPerAttendee(eventId);
        assertThat(latest)
                .as("Exactly one current attendance entry must exist for snowflake 111")
                .hasSize(1);
        assertThat(latest.get(0).getSnowflake())
                .as("The single entry must belong to snowflake 111")
                .isEqualTo("111");
    }

    // -----------------------------------------------------------------------
    // Test 3: Concurrent balance deductions must not silently over-debit
    // -----------------------------------------------------------------------

    /**
     * Exposes the optimistic-locking gap in {@link UserBalanceService#deduct}.
     * Seeds a user with 1000 coins, then launches 5 threads each deducting 200.
     *
     * <p>The correct outcome is that the balance ends at 0 (all 5 succeed) or higher
     * than 0 (some are serialised). What must NOT happen is a final balance below -200
     * (which would indicate a lost-update where two threads both read the same balance
     * and overwrite each other).
     *
     * <p>NOTE: {@code UserBalanceService.deduct()} currently lacks a SELECT FOR UPDATE
     * or @Version optimistic lock, so this test may reveal a genuine lost-update
     * race. If so, this test intentionally FAILS to surface the bug.
     */
    @Test
    void concurrentBalanceDeductions_doNotLoseUpdates() throws Exception {
        int threads = 5;
        long deductAmount = 200L;
        long startingBalance = 1000L;
        String snowflake = "balance-test-user";

        userBalanceService.credit(snowflake, startingBalance);

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(CompletableFuture.runAsync(
                    () -> {
                        try {
                            startGate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        try {
                            userBalanceService.deduct(snowflake, deductAmount);
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            failureCount.incrementAndGet();
                        }
                    },
                    pool));
        }

        startGate.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        pool.shutdown();

        long finalBalance = userBalanceService.getBalance(snowflake);
        long totalDeducted = startingBalance - finalBalance;

        // Each successful deduction must be reflected in the balance.
        // The final balance must equal: startingBalance - (successCount * deductAmount).
        assertThat(totalDeducted)
                .as(
                        "Total deducted (%d) must equal successCount (%d) * deductAmount (%d). "
                                + "A mismatch means concurrent writes silently lost an update.",
                        totalDeducted, successCount.get(), deductAmount)
                .isEqualTo((long) successCount.get() * deductAmount);

        // The balance must never go below the natural floor given the number of
        // threads and the starting amount. If all 5 succeeded and the balance is
        // not -1000, updates were not over-applied.
        assertThat(finalBalance)
                .as("Balance must not go below zero when only 5 * 200 = 1000 was deducted from 1000")
                .isGreaterThanOrEqualTo(0L);
    }

    // -----------------------------------------------------------------------
    // Test 4: Concurrent event saves do not collide
    // -----------------------------------------------------------------------

    /**
     * Five threads each persist a distinct Event with auto-generated UUID.
     * After completion, all 5 rows must be visible.
     */
    @Test
    void concurrentEventSaves_doNotCollide() throws Exception {
        int n = 5;
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);

        List<UUID> savedIds = new ArrayList<>();
        Object lock = new Object();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(CompletableFuture.runAsync(
                    () -> {
                        try {
                            startGate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        long id = messageIdCounter.incrementAndGet() + idx * 1000L;
                        Event event = new Event(
                                id,
                                GUILD_ID,
                                id,
                                "event-" + idx,
                                "creator-" + idx,
                                ZonedDateTime.now().plusDays(1),
                                "desc-" + idx);
                        event.setState(EventState.PLANNED);
                        UUID savedId = eventRepository.save(event).getId();
                        synchronized (lock) {
                            savedIds.add(savedId);
                        }
                    },
                    pool));
        }

        startGate.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        pool.shutdown();

        assertThat(savedIds)
                .as("All 5 events must have been persisted with distinct UUIDs")
                .hasSize(n)
                .doesNotHaveDuplicates();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private UUID seedEvent(String name) {
        long id = messageIdCounter.incrementAndGet();
        Event event =
                new Event(id, GUILD_ID, id, name, "seeder", ZonedDateTime.now().plusDays(1), "seeded");
        event.setState(EventState.PLANNED);
        return eventRepository.save(event).getId();
    }
}
