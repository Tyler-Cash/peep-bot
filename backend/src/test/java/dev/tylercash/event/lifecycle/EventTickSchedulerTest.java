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
    // ARCHIVAL: archive at 10:00 (event-zone) on completion-date + 2 days. Constant for all
    // guilds. completion-date = date of event.dateTime + 6h. State POST_COMPLETED.
    // FIXED_NOW = 2026-05-04 18:00Z.
    // -----------------------------------------------------------------------------------------

    @Test
    void emit_publishesArchivalPastDueMoment_onlyOnce() {
        // Event 2026-05-02 06:00Z → completion 2026-05-02 12:00Z → due 2026-05-04 10:00Z.
        // FIXED_NOW 2026-05-04 18:00Z is past → due.
        Event e = saveEvent(ZonedDateTime.of(2026, 5, 2, 6, 0, 0, 0, ZoneOffset.UTC), EventState.POST_COMPLETED);

        scheduler.emit();
        scheduler.emit();

        verify(publisher, times(1)).publish(new EventLifecycleEvent.EventArchivalDue(e.getId()));
        assertThat(tickLog.existsById(new EventTickLogId(e.getId(), "ARCHIVAL")))
                .isTrue();
    }

    @Test
    void emit_doesNotPublishArchival_whenBefore10amOnDueDay() {
        // Event 2026-05-02 06:00Z → due 2026-05-04 10:00Z. Pretend "now" is 2026-05-04 09:00Z.
        saveEvent(ZonedDateTime.of(2026, 5, 2, 6, 0, 0, 0, ZoneOffset.UTC), EventState.POST_COMPLETED);
        when(clock.instant())
                .thenReturn(
                        ZonedDateTime.of(2026, 5, 4, 9, 0, 0, 0, ZoneOffset.UTC).toInstant());

        scheduler.emit();

        verify(publisher, times(0)).publish(any(EventLifecycleEvent.EventArchivalDue.class));
    }

    @Test
    void emit_doesNotPublishArchival_onCompletionDayPlusOne() {
        // Event ~20h before now: completion-date is yesterday, due is tomorrow 10am → NOT due
        // (covers "not the next day, but the day after").
        saveEvent(FIXED_NOW.minusHours(20), EventState.POST_COMPLETED);

        scheduler.emit();

        verify(publisher, times(0)).publish(any(EventLifecycleEvent.EventArchivalDue.class));
    }

    @Test
    void emit_archivalIgnoresPerGuildArchiveDays() {
        // archive_days only governs retention in the archived category, not the completion→archive
        // delay. Setting archive_days=90 on a 3-day-old event must NOT prevent archival.
        long guildId = dev.tylercash.event.test.TestIds.nextLong();
        insertGuildWithArchiveDays(guildId, 90);
        Event e =
                saveEvent(ZonedDateTime.of(2026, 5, 1, 6, 0, 0, 0, ZoneOffset.UTC), EventState.POST_COMPLETED, guildId);

        scheduler.emit();

        verify(publisher).publish(new EventLifecycleEvent.EventArchivalDue(e.getId()));
    }

    // -----------------------------------------------------------------------------------------
    // DELETE_CANCELLED: event.dateTime < now - 3 months. archive_days does not apply.
    // -----------------------------------------------------------------------------------------

    @Test
    void emit_publishesDeleteForOldCancelledEvent_onlyOnce() {
        Event e = saveEvent(FIXED_NOW.minusMonths(4), EventState.CANCELLED);

        scheduler.emit();
        scheduler.emit();

        verify(publisher, times(1)).publish(new EventLifecycleEvent.EventDeleteRequested(e.getId()));
        assertThat(tickLog.existsById(new EventTickLogId(e.getId(), "DELETE_CANCELLED")))
                .isTrue();
    }

    @Test
    void emit_doesNotPublishDeleteForCancelled_whenWithin3Months() {
        saveEvent(FIXED_NOW.minusMonths(2), EventState.CANCELLED);

        scheduler.emit();

        verify(publisher, times(0)).publish(any(EventLifecycleEvent.EventDeleteRequested.class));
    }

    // -----------------------------------------------------------------------------------------
    // DELETE_ARCHIVED: fires guild.archive_days after the archive moment (event.dateTime + 6h →
    // completion-date + 2 days at 10:00). Default 90 days when no guild row exists.
    // -----------------------------------------------------------------------------------------

    @Test
    void emit_publishesDeleteForArchivedPastDefaultRetention_onlyOnce() {
        // No guild row → default 90. Event 2026-01-01 → archive ~2026-01-03 10:00 → delete due
        // ~2026-04-03 10:00. FIXED_NOW 2026-05-04 is past → due.
        Event e = saveEvent(ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC), EventState.ARCHIVED);

        scheduler.emit();
        scheduler.emit();

        verify(publisher, times(1)).publish(new EventLifecycleEvent.EventDeleteRequested(e.getId()));
        assertThat(tickLog.existsById(new EventTickLogId(e.getId(), "DELETE_ARCHIVED")))
                .isTrue();
    }

    @Test
    void emit_publishesDeleteForArchivedUsingPerGuildRetention() {
        // archive_days=7. Event 2026-04-23 06:00Z → archive 2026-04-25 10:00Z → delete due
        // 2026-05-02 10:00Z. FIXED_NOW 2026-05-04 18:00Z is past → due.
        long guildId = dev.tylercash.event.test.TestIds.nextLong();
        insertGuildWithArchiveDays(guildId, 7);
        Event e = saveEvent(ZonedDateTime.of(2026, 4, 23, 6, 0, 0, 0, ZoneOffset.UTC), EventState.ARCHIVED, guildId);

        scheduler.emit();

        verify(publisher).publish(new EventLifecycleEvent.EventDeleteRequested(e.getId()));
        assertThat(tickLog.existsById(new EventTickLogId(e.getId(), "DELETE_ARCHIVED")))
                .isTrue();
    }

    @Test
    void emit_doesNotPublishDeleteForArchived_whenInsidePerGuildRetention() {
        // archive_days=30. 10-day-old event is well inside the retention → NOT due.
        long guildId = dev.tylercash.event.test.TestIds.nextLong();
        insertGuildWithArchiveDays(guildId, 30);
        Event e = saveEvent(FIXED_NOW.minusDays(10), EventState.ARCHIVED, guildId);

        scheduler.emit();

        assertThat(tickLog.existsById(new EventTickLogId(e.getId(), "DELETE_ARCHIVED")))
                .isFalse();
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
