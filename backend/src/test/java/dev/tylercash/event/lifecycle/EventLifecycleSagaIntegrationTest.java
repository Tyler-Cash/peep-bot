package dev.tylercash.event.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.EventService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.immich.ImmichService;
import dev.tylercash.event.rewind.EmbeddingService;
import dev.tylercash.event.test.SharedPostgres;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        classes = PeepBotApplication.class,
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "spring.security.oauth2.client.registration.discord.client-id=test",
            "spring.security.oauth2.client.registration.discord.client-secret=test",
            "dev.tylercash.discord.token=dummy",
            "dev.tylercash.discord.guild-id=0"
        })
@ActiveProfiles("local")
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
@Import(EventLifecycleSagaIntegrationTest.SyncEventBusConfig.class)
class EventLifecycleSagaIntegrationTest {

    /**
     * Run dispatcher tasks on the caller's thread instead of the {@code event-bus-N} pool. The
     * listener chain (EventCreated → EventChannelReady → … → Immich Album Post) becomes a deep
     * synchronous recursion driven by Spring's AFTER_COMMIT handler, which lets each
     * {@code awaitOutboxSuccess} resolve in milliseconds instead of polling for seconds.
     */
    @TestConfiguration
    static class SyncEventBusConfig {
        @Bean
        @Primary
        AsyncTaskExecutor eventBusExecutor() {
            return new TaskExecutorAdapter(new SyncTaskExecutor());
        }

        /**
         * Captures every Observation that completes during this test. Read by
         * {@link #dumpObservationsForEvent(UUID)} when {@link #awaitOutboxSuccess} times out,
         * so a stuck listener prints its actual span tree (name, duration, error, attrs)
         * instead of just "status=IN_PROGRESS: null".
         *
         * <p>Spring Boot's {@code ObservationAutoConfiguration} registers all
         * {@code ObservationHandler<?>} beans onto the shared {@code ObservationRegistry}.
         */
        @Bean
        CapturingObservationHandler capturingObservationHandler() {
            return new CapturingObservationHandler();
        }
    }

    static final class CapturingObservationHandler implements ObservationHandler<Observation.Context> {
        final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(context);
        }
    }

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @MockitoBean
    EmbeddingService embeddingService;

    @MockitoBean
    ImmichService immichService;

    @MockitoBean
    Clock clock;

    @Autowired
    EventService eventService;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    ListenerInvocationRepository listenerInvocationRepository;

    @Autowired
    EventTickScheduler eventTickScheduler;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CapturingObservationHandler observations;

    /** Mutable clock — advance with {@link #advanceTo(Instant)}. */
    private final AtomicReference<Instant> nowHolder = new AtomicReference<>(Instant.parse("2026-05-04T10:00:00Z"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.registerIsolatedDatabase(registry, EventLifecycleSagaIntegrationTest.class);
    }

    @BeforeEach
    void setUp() {
        // Wire the mutable clock
        when(clock.instant()).thenAnswer(inv -> nowHolder.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        // Stub DiscordService so channel/message creation doesn't crash
        TextChannel mockChannel = mock(TextChannel.class);
        when(mockChannel.getIdLong()).thenReturn(99_001L);

        Message mockMessage = mock(Message.class);
        when(mockMessage.getIdLong()).thenReturn(99_002L);
        when(mockMessage.getGuildIdLong()).thenReturn(111L);

        when(discordService.createEventChannel(any())).thenReturn(mockChannel);
        when(discordService.getChannel(any())).thenReturn(mockChannel);
        when(discordService.postEventMessage(any(), any())).thenReturn(mockMessage);

        // Stub ImmichService — immich is disabled by default, but stub returns anyway
        when(immichService.createAlbum(any(), any())).thenReturn(Optional.of("album-id-stub"));
        when(immichService.createSharedLink(any())).thenReturn(Optional.of("share-key-stub"));
        when(immichService.getShareUrl(any())).thenReturn("https://share/stub");

        // EmbeddingService.classifyEvent is void — Mockito default (no-op) is fine
    }

    // ------------------------------------------------------------------
    // Clock helpers
    // ------------------------------------------------------------------

    private void advanceTo(Instant t) {
        nowHolder.set(t);
    }

    // ------------------------------------------------------------------
    // Polling helpers (hand-rolled — Awaitility is not on classpath)
    // ------------------------------------------------------------------

    private static final long POLL_INTERVAL_MS = 50;
    // The SyncEventBusConfig override aims to drive listeners on the caller's thread,
    // but Spring's bean overriding does not always replace the production
    // `eventBusExecutor` (depends on @Configuration processing order under @SpringBootTest).
    // Under parallel-test load the async event-bus pool can sit queued for several
    // seconds; budget enough headroom so the saga doesn't flake when bundled.
    private static final long TIMEOUT_MS = 30_000;

    private void awaitState(UUID eventId, EventState expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            EventState actual =
                    eventRepository.findById(eventId).map(Event::getState).orElse(null);
            if (expected == actual) return;
            Thread.sleep(POLL_INTERVAL_MS);
        }
        EventState actual =
                eventRepository.findById(eventId).map(Event::getState).orElse(null);
        throw new AssertionError("Timed out waiting for state " + expected + " but was " + actual);
    }

    private void awaitOutboxSuccess(UUID eventId, String lifecycleEventType, String listenerName)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        ListenerInvocationId key = new ListenerInvocationId(eventId, lifecycleEventType, listenerName);
        while (System.currentTimeMillis() < deadline) {
            ListenerInvocation row = listenerInvocationRepository.findById(key).orElse(null);
            if (row != null && row.getStatus() == ListenerInvocationStatus.SUCCESS) return;
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError(buildTimeoutMessage(eventId, lifecycleEventType, listenerName));
    }

    /**
     * Build a diagnostic dump for an {@code awaitOutboxSuccess} timeout. Three layers:
     *
     * <ol>
     *   <li>The stuck row's full state (status, attempts, lastError, timing).
     *   <li>Every outbox row for {@code eventId} — usually shows whether the upstream
     *       listener actually completed, which catches "downstream stuck because upstream
     *       silently didn't fire."
     *   <li>Every captured Observation tagged with this {@code event.id} — the actual span
     *       tree with names, durations, and errors. Replaces the old "thread dump on
     *       timeout" idea: the data is what we'd query Tempo for in prod.
     * </ol>
     */
    private String buildTimeoutMessage(UUID eventId, String lifecycleEventType, String listenerName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Timed out waiting for SUCCESS on [")
                .append(lifecycleEventType)
                .append(" / ")
                .append(listenerName)
                .append("] (eventId=")
                .append(eventId)
                .append(")\n");

        ListenerInvocationId key = new ListenerInvocationId(eventId, lifecycleEventType, listenerName);
        ListenerInvocation stuck = listenerInvocationRepository.findById(key).orElse(null);
        sb.append("  stuck row: ").append(rowSummary(stuck)).append('\n');

        sb.append("  all listener_invocation rows for event:\n");
        List<ListenerInvocation> rows = listenerInvocationRepository.findAll().stream()
                .filter(r -> eventId.equals(r.getEventId()))
                .sorted(Comparator.comparing(
                        ListenerInvocation::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (rows.isEmpty()) sb.append("    (none)\n");
        else
            for (ListenerInvocation r : rows)
                sb.append("    ").append(rowSummary(r)).append('\n');

        sb.append("  observations for event.id=").append(eventId).append(":\n");
        String eventIdStr = eventId.toString();
        // CopyOnWriteArrayList preserves insertion order, which equals onStop order — i.e.
        // observations sorted by completion. Good enough to spot "where the chain stopped."
        List<Observation.Context> matching = observations.stopped.stream()
                .filter(c -> eventIdStr.equals(keyValue(c, "event.id")))
                .toList();
        if (matching.isEmpty())
            sb.append("    (none — listener never started or never reached the instrumented path)\n");
        else
            for (Observation.Context c : matching)
                sb.append("    ").append(observationSummary(c)).append('\n');

        return sb.toString();
    }

    private static String rowSummary(ListenerInvocation r) {
        if (r == null) return "<missing>";
        return r.getLifecycleEventType() + "/" + r.getListenerName()
                + " status=" + r.getStatus()
                + " attempts=" + r.getAttempts()
                + " lastAttemptAt=" + r.getLastAttemptAt()
                + " nextRetryAt=" + r.getNextRetryAt()
                + " updatedAt=" + r.getUpdatedAt()
                + " lastError=" + r.getLastError();
    }

    private static String observationSummary(Observation.Context c) {
        Throwable err = c.getError();
        return c.getName()
                + " lowCardKVs=" + c.getLowCardinalityKeyValues()
                + " highCardKVs=" + c.getHighCardinalityKeyValues()
                + (err == null ? "" : " error=" + err.getClass().getSimpleName() + ": " + err.getMessage());
    }

    private static String keyValue(Observation.Context c, String key) {
        var low = c.getLowCardinalityKeyValue(key);
        if (low != null) return low.getValue();
        var high = c.getHighCardinalityKeyValue(key);
        return high == null ? null : high.getValue();
    }

    /**
     * Emit a tick. {@code EventTickScheduler.emit()} is {@code @Transactional}, so the
     * {@code @TransactionalEventListener(AFTER_COMMIT)} in PostCommitDispatcher fires on commit
     * and the listener chain proceeds. The {@link DurableListenerRetryPoller} is a safety net for
     * crashed dispatch attempts and is not required to drive the happy path.
     */
    private void emitTick() {
        eventTickScheduler.emit();
    }

    // ------------------------------------------------------------------
    // The saga
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void fullSaga_createsEventAndDrivesItThroughLifecycleToDeleted() throws InterruptedException {
        // ── Step 1: create the event ──────────────────────────────────────────────
        // Clock is at 2026-05-04T10:00Z. Schedule the event for 2026-05-04T17:00Z so
        // it falls in the pre-notify window when clock advances to 17:30Z (i.e. now < eventTime,
        // eventTime < now+2h).
        advanceTo(Instant.parse("2026-05-04T10:00:00Z"));
        long uniqueId = dev.tylercash.event.test.TestIds.nextLong();
        ZonedDateTime eventDateTime = ZonedDateTime.of(2026, 5, 4, 17, 0, 0, 0, ZoneOffset.UTC);
        Event event =
                new Event(uniqueId, 111L, uniqueId, "Saga Test Event", "creator-snowflake", eventDateTime, "desc");
        eventService.createEvent(event);
        UUID id = event.getId();
        assertThat(id).isNotNull();

        // ── Step 2: init chain → PLANNED ─────────────────────────────────────────
        awaitOutboxSuccess(id, "EventCreated", "Discord Channel Init");
        awaitOutboxSuccess(id, "EventChannelReady", "Discord Roles Init");
        awaitOutboxSuccess(id, "EventRolesReady", "Event Classify");
        awaitOutboxSuccess(id, "EventClassified", "Event Init Complete");
        awaitState(id, EventState.PLANNED);

        // ── Step 3: pre-notify ───────────────────────────────────────────────────
        // Advance to 16:30 so event (17:00) is inside the (now, now+2h) window.
        advanceTo(Instant.parse("2026-05-04T16:30:00Z"));
        emitTick();
        awaitOutboxSuccess(id, "EventPreNotifyDue", "Pre-Event Notification");
        awaitState(id, EventState.PRE_NOTIFIED);

        // Immich is disabled, so ImmichAlbumPrepListener short-circuits to SUCCESS.
        awaitOutboxSuccess(id, "EventPreNotified", "Immich Album Prep");

        // ── Step 4: completion ───────────────────────────────────────────────────
        // Event ends at 17:00; +6h = 23:00. Advance past that.
        advanceTo(Instant.parse("2026-05-04T23:30:00Z"));
        emitTick();
        awaitOutboxSuccess(id, "EventCompletionDue", "Event Complete");
        awaitState(id, EventState.POST_COMPLETED);

        // Immich is disabled, so ImmichAlbumPostListener short-circuits to SUCCESS.
        awaitOutboxSuccess(id, "EventCompleted", "Immich Album Post");

        // ── Step 5: archival ─────────────────────────────────────────────────────
        // Archive timing = 10:00 (event zone) on completion-date + 2 days. Constant for all
        // guilds. Event 2026-05-04 17:00Z → completion-date 2026-05-04 → due 2026-05-06 10:00Z.
        // The guild row is inserted now to set archive_days=7 for Step 6's deletion timing
        // (default 90 would be impractical for a test).
        jdbc.update("INSERT INTO guild (guild_id, events_role, organiser_role, emoji_accepted, emoji_declined, "
                + "emoji_maybe, joined_at, active, immich_enabled, google_autocomplete_enabled, "
                + "rewind_enabled, contracts_enabled, tfnsw_enabled, archive_days, anyone_can_create) "
                + "VALUES (111, 'events', 'event-organiser', '✅', '❌', '❓', now(), true, false, "
                + "false, false, false, false, 7, true) ON CONFLICT (guild_id) DO UPDATE SET archive_days = 7");
        advanceTo(Instant.parse("2026-05-06T10:30:00Z"));
        emitTick();
        awaitOutboxSuccess(id, "EventArchivalDue", "Event Archive");
        awaitState(id, EventState.ARCHIVED);

        // ── Step 6: delete ───────────────────────────────────────────────────────
        // Deletion = archive moment + guild.archive_days. Archive moment 2026-05-06 10:00Z plus
        // 7 days = 2026-05-13 10:00Z. Advance just past that.
        advanceTo(Instant.parse("2026-05-13T10:30:00Z"));
        emitTick();
        awaitOutboxSuccess(id, "EventDeleteRequested", "Event Delete");
        awaitState(id, EventState.DELETED);

        // ── Final: no FAILED rows for this event ─────────────────────────────────
        List<ListenerInvocation> failedRows = listenerInvocationRepository.findAll().stream()
                .filter(r -> r.getEventId().equals(id))
                .filter(r -> r.getStatus() == ListenerInvocationStatus.FAILED)
                .toList();
        assertThat(failedRows)
                .as("No listener invocations should remain in FAILED state")
                .isEmpty();
    }
}
