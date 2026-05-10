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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
class EventLifecycleSagaIntegrationTest {

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

    private static final long POLL_INTERVAL_MS = 100;
    private static final long TIMEOUT_MS = 20_000;

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
        ListenerInvocation row = listenerInvocationRepository.findById(key).orElse(null);
        String status = row == null ? "<missing>" : row.getStatus().name();
        String error = row == null ? "" : (": " + row.getLastError());
        throw new AssertionError("Timed out waiting for SUCCESS on [" + lifecycleEventType + " / " + listenerName
                + "]; current status=" + status + error);
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
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
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
        // Archive time = event.dateTime.plusDays(1) @ 22:00 = 2026-05-05T22:00Z.
        // Advance past that threshold.
        advanceTo(Instant.parse("2026-05-05T23:00:00Z"));
        emitTick();
        awaitOutboxSuccess(id, "EventArchivalDue", "Event Archive");
        awaitState(id, EventState.ARCHIVED);

        // ── Step 6: delete ───────────────────────────────────────────────────────
        // Retention = 3 months after event.dateTime (2026-05-04) → 2026-08-04.
        // Advance 3 months + 1 day.
        advanceTo(Instant.parse("2026-08-05T00:00:00Z"));
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
