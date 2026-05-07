package dev.tylercash.event.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
class EventTickSchedulerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @MockitoBean
    EventLifecyclePublisher publisher;

    @MockitoBean
    Clock clock;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    EventTickLogRepository tickLog;

    @Autowired
    EventTickScheduler scheduler;

    @Autowired
    JdbcTemplate jdbc;

    private static final AtomicLong messageIdCounter = new AtomicLong(50_000);

    // Fixed "now" for all tests: 2026-05-04T18:00:00Z
    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.of(2026, 5, 4, 18, 0, 0, 0, ZoneOffset.UTC);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(FIXED_NOW.toInstant());
        when(clock.getZone()).thenReturn(FIXED_NOW.getZone());

        jdbc.execute("DELETE FROM event_tick_log");
        jdbc.execute("DELETE FROM listener_invocation");
        jdbc.execute("DELETE FROM event_category");
        jdbc.execute("DELETE FROM attendance");
        jdbc.execute("DELETE FROM event");
    }

    private Event saveEvent(ZonedDateTime dateTime, EventState state) {
        long id = messageIdCounter.incrementAndGet();
        Event e = new Event(id, 111L, id, "Test Event", "creator", dateTime, "desc");
        e.setState(state);
        return eventRepository.save(e);
    }

    // -----------------------------------------------------------------------------------------
    // PRE_NOTIFY: event.dateTime in (now, now+2h), state PLANNED
    // -----------------------------------------------------------------------------------------

    @Test
    void emit_publishesPreNotifyForEventInWindow_onlyOnce() {
        // dateTime = now + 1h → inside (now, now+2h)
        Event e = saveEvent(FIXED_NOW.plusHours(1), EventState.PLANNED);

        scheduler.emit();
        scheduler.emit(); // second call must not re-publish

        verify(publisher, times(1)).publish(any(EventLifecycleEvent.EventPreNotifyDue.class));
        assertThat(tickLog.existsById(new EventTickLogId(e.getId(), "PRE_NOTIFY")))
                .isTrue();
    }

    @Test
    void emit_doesNotPublishPreNotify_whenEventIsOutsideWindow() {
        // dateTime = now + 3h → outside (now, now+2h)
        saveEvent(FIXED_NOW.plusHours(3), EventState.PLANNED);

        scheduler.emit();

        verify(publisher, times(0)).publish(any(EventLifecycleEvent.EventPreNotifyDue.class));
    }

    @Test
    void emit_doesNotPublishPreNotify_whenEventAlreadyPastStart() {
        // dateTime = now - 1h → already started, outside window
        saveEvent(FIXED_NOW.minusHours(1), EventState.PLANNED);

        scheduler.emit();

        verify(publisher, times(0)).publish(any(EventLifecycleEvent.EventPreNotifyDue.class));
    }

    // -----------------------------------------------------------------------------------------
    // COMPLETION: event.dateTime < now - 6h, state PRE_NOTIFIED
    // -----------------------------------------------------------------------------------------

    @Test
    void emit_publishesCompletionForEventPastSixHours_onlyOnce() {
        // dateTime = now - 7h → past the 6h threshold
        Event e = saveEvent(FIXED_NOW.minusHours(7), EventState.PRE_NOTIFIED);

        scheduler.emit();
        scheduler.emit();

        verify(publisher, times(1)).publish(any(EventLifecycleEvent.EventCompletionDue.class));
        assertThat(tickLog.existsById(new EventTickLogId(e.getId(), "COMPLETION")))
                .isTrue();
    }

    @Test
    void emit_doesNotPublishCompletion_whenWithinSixHours() {
        // dateTime = now - 5h → not yet past 6h threshold
        saveEvent(FIXED_NOW.minusHours(5), EventState.PRE_NOTIFIED);

        scheduler.emit();

        verify(publisher, times(0)).publish(any(EventLifecycleEvent.EventCompletionDue.class));
    }

    // -----------------------------------------------------------------------------------------
    // ARCHIVAL: event.dateTime < now - 22h, state POST_COMPLETED
    // -----------------------------------------------------------------------------------------

    @Test
    void emit_publishesArchivalForOldPostCompletedEvent_onlyOnce() {
        // dateTime = now - 30h → well past the 22h conservative threshold
        Event e = saveEvent(FIXED_NOW.minusHours(30), EventState.POST_COMPLETED);

        scheduler.emit();
        scheduler.emit();

        verify(publisher, times(1)).publish(any(EventLifecycleEvent.EventArchivalDue.class));
        assertThat(tickLog.existsById(new EventTickLogId(e.getId(), "ARCHIVAL")))
                .isTrue();
    }

    @Test
    void emit_doesNotPublishArchival_whenTooRecent() {
        // dateTime = now - 10h → inside the 22h threshold
        saveEvent(FIXED_NOW.minusHours(10), EventState.POST_COMPLETED);

        scheduler.emit();

        verify(publisher, times(0)).publish(any(EventLifecycleEvent.EventArchivalDue.class));
    }

    // -----------------------------------------------------------------------------------------
    // DELETE: event.dateTime < now - 3 months, states CANCELLED and ARCHIVED
    // -----------------------------------------------------------------------------------------

    @Test
    void emit_publishesDeleteForOldCancelledEvent_onlyOnce() {
        // dateTime = now - 4 months → past the 3-month delete threshold
        Event e = saveEvent(FIXED_NOW.minusMonths(4), EventState.CANCELLED);

        scheduler.emit();
        scheduler.emit();

        verify(publisher, times(1)).publish(any(EventLifecycleEvent.EventDeleteRequested.class));
        assertThat(tickLog.existsById(new EventTickLogId(e.getId(), "DELETE_CANCELLED")))
                .isTrue();
    }

    @Test
    void emit_publishesDeleteForOldArchivedEvent_onlyOnce() {
        // dateTime = now - 4 months → past the 3-month delete threshold
        Event e = saveEvent(FIXED_NOW.minusMonths(4), EventState.ARCHIVED);

        scheduler.emit();
        scheduler.emit();

        verify(publisher, times(1)).publish(any(EventLifecycleEvent.EventDeleteRequested.class));
        assertThat(tickLog.existsById(new EventTickLogId(e.getId(), "DELETE_ARCHIVED")))
                .isTrue();
    }

    @Test
    void emit_doesNotPublishDelete_whenWithinRetentionWindow() {
        // dateTime = now - 2 months → inside the 3-month threshold
        saveEvent(FIXED_NOW.minusMonths(2), EventState.CANCELLED);

        scheduler.emit();

        verify(publisher, times(0)).publish(any(EventLifecycleEvent.EventDeleteRequested.class));
    }

    @Test
    void emit_publishesDeleteForBothCancelledAndArchived_independently() {
        Event cancelled = saveEvent(FIXED_NOW.minusMonths(4), EventState.CANCELLED);
        Event archived = saveEvent(FIXED_NOW.minusMonths(5), EventState.ARCHIVED);

        scheduler.emit();

        verify(publisher, times(2)).publish(any(EventLifecycleEvent.EventDeleteRequested.class));
        assertThat(tickLog.existsById(new EventTickLogId(cancelled.getId(), "DELETE_CANCELLED")))
                .isTrue();
        assertThat(tickLog.existsById(new EventTickLogId(archived.getId(), "DELETE_ARCHIVED")))
                .isTrue();
    }

    // -----------------------------------------------------------------------------------------
    // State isolation: wrong state never triggers the wrong tick
    // -----------------------------------------------------------------------------------------

    @Test
    void emit_doesNotPublishPreNotify_forNonPlannedState() {
        // PRE_NOTIFIED event in the pre-notify window should not get PRE_NOTIFY tick
        saveEvent(FIXED_NOW.plusHours(1), EventState.PRE_NOTIFIED);

        scheduler.emit();

        verify(publisher, times(0)).publish(any(EventLifecycleEvent.EventPreNotifyDue.class));
    }
}
