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
import dev.tylercash.event.test.SharedPostgres;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
class EventTickSchedulerTest {

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

    // Fixed "now" for all tests: 2026-05-04T18:00:00Z
    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.of(2026, 5, 4, 18, 0, 0, 0, ZoneOffset.UTC);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.registerIsolatedDatabase(registry, EventTickSchedulerTest.class);
    }

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(FIXED_NOW.toInstant());
        when(clock.getZone()).thenReturn(FIXED_NOW.getZone());
    }

    private Event saveEvent(ZonedDateTime dateTime, EventState state) {
        return saveEvent(dateTime, state, 111L);
    }

    private Event saveEvent(ZonedDateTime dateTime, EventState state, long guildId) {
        long id = dev.tylercash.event.test.TestIds.nextLong();
        Event e = new Event(id, guildId, id, "Test Event", "creator", dateTime, "desc");
        e.setState(state);
        return eventRepository.save(e);
    }

    private void insertGuildWithArchiveDays(long guildId, int archiveDays) {
        jdbc.update(
                "INSERT INTO guild (guild_id, events_role, organiser_role, emoji_accepted, emoji_declined, "
                        + "emoji_maybe, joined_at, active, immich_enabled, google_autocomplete_enabled, "
                        + "rewind_enabled, contracts_enabled, tfnsw_enabled, archive_days, anyone_can_create) "
                        + "VALUES (?, 'events', 'event-organiser', '✅', '❌', '❓', now(), true, false, false, "
                        + "false, false, false, ?, true) ON CONFLICT (guild_id) DO UPDATE SET archive_days = EXCLUDED.archive_days",
                guildId,
                archiveDays);
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
    // ARCHIVAL: per-guild archive_days threshold (default 90), state POST_COMPLETED.
    // The candidate window is now - 7 days (smallest legal value); per-event the scheduler
    // checks the owning guild's configured threshold before publishing.
    // -----------------------------------------------------------------------------------------

    @Test
    void emit_publishesArchivalForEventOlderThanDefaultThreshold_onlyOnce() {
        // dateTime = now - 91 days → past the default 90-day threshold (no guild row → default)
        Event e = saveEvent(FIXED_NOW.minusDays(91), EventState.POST_COMPLETED);

        scheduler.emit();
        scheduler.emit();

        verify(publisher, times(1)).publish(any(EventLifecycleEvent.EventArchivalDue.class));
        assertThat(tickLog.existsById(new EventTickLogId(e.getId(), "ARCHIVAL")))
                .isTrue();
    }

    @Test
    void emit_doesNotPublishArchival_whenInsideDefaultThreshold() {
        // dateTime = now - 8 days → past the 7-day candidate window, but well inside 90-day default
        saveEvent(FIXED_NOW.minusDays(8), EventState.POST_COMPLETED);

        scheduler.emit();

        verify(publisher, times(0)).publish(any(EventLifecycleEvent.EventArchivalDue.class));
    }

    @Test
    void emit_publishesArchivalUsingPerGuildThreshold() {
        // Unique guild with archive_days=7: an 8-day-old event must publish.
        long guildId = dev.tylercash.event.test.TestIds.nextLong();
        insertGuildWithArchiveDays(guildId, 7);
        Event e = saveEvent(FIXED_NOW.minusDays(8), EventState.POST_COMPLETED, guildId);

        scheduler.emit();

        // Scope to this event — other tests in this class share the same scheduler invocation.
        assertThat(tickLog.existsById(new EventTickLogId(e.getId(), "ARCHIVAL")))
                .isTrue();
        verify(publisher).publish(new EventLifecycleEvent.EventArchivalDue(e.getId()));
    }

    @Test
    void emit_doesNotPublishArchival_whenInsidePerGuildThreshold() {
        // Unique guild with archive_days=30: an 8-day-old event must NOT publish.
        long guildId = dev.tylercash.event.test.TestIds.nextLong();
        insertGuildWithArchiveDays(guildId, 30);
        Event e = saveEvent(FIXED_NOW.minusDays(8), EventState.POST_COMPLETED, guildId);

        scheduler.emit();

        assertThat(tickLog.existsById(new EventTickLogId(e.getId(), "ARCHIVAL")))
                .isFalse();
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
