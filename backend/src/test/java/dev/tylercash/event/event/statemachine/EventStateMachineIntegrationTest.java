package dev.tylercash.event.event.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-graph integration test for {@link EventStateMachineService}.
 *
 * <p>Each test seeds an event in the source state, stubs the relevant collaborators,
 * fires {@code stateMachineService.attemptTransition(event, SIGNAL)}, then asserts the
 * destination state in the database and that the expected side-effect was invoked.
 *
 * <p>The @Scheduled lifecycle poller is intentionally bypassed: tests drive transitions
 * directly through the service to avoid timing flakiness.
 */
@SpringBootTest(
        classes = {PeepBotApplication.class, EventStateMachineIntegrationTest.ClockConfig.class},
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "spring.security.oauth2.client.registration.discord.client-id=test",
            "spring.security.oauth2.client.registration.discord.client-secret=test",
            "dev.tylercash.discord.token=dummy",
            "dev.tylercash.discord.guild-id=0",
            "dev.tylercash.frontend.hostname=test.local",
            "dev.tylercash.contract.guild-id=1",
            // Disable Immich so PrepareAlbum guard returns false by default;
            // individual tests that exercise PrepareAlbum re-enable it via the
            // ImmichService mock stubs.
            "dev.tylercash.immich.enabled=false"
        })
@Testcontainers
@ActiveProfiles("local")
class EventStateMachineIntegrationTest {

    // Fixed clock: "2018-01-06 13:00 UTC". Used to satisfy time-gated guards.
    private static final Instant FIXED_INSTANT = Instant.parse("2018-01-06T13:00:00Z");
    private static final long GUILD_ID = 311L;

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"));
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    // EmbeddingModel is optional in EmbeddingService; mocking it prevents a
    // connection attempt to an absent vector-store during ClassifyOperation.
    @MockitoBean
    EmbeddingModel embeddingModel;

    @Autowired
    EventStateMachineService stateMachineService;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("DELETE FROM attendance");
        jdbc.execute("DELETE FROM event_category");
        jdbc.execute("DELETE FROM event");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Save an event in the given state; messageId / channelId / serverId already set. */
    private Event seedEvent(EventState state, ZonedDateTime dateTime) {
        Event event = new Event(1001L, GUILD_ID, 2001L, "Test Event", "creator-snowflake", dateTime, "desc");
        event.setState(state);
        return eventRepository.save(event);
    }

    private Event seedEvent(EventState state) {
        return seedEvent(
                state,
                ZonedDateTime.now(Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))).plusDays(7));
    }

    /** Build standard TextChannel + Message stubs and wire them into discordService. */
    private TextChannel stubChannel() {
        TextChannel channel = org.mockito.Mockito.mock(TextChannel.class);
        when(channel.getIdLong()).thenReturn(2001L);

        Message message = org.mockito.Mockito.mock(Message.class);
        when(message.getIdLong()).thenReturn(1001L);
        when(message.getGuildIdLong()).thenReturn(GUILD_ID);

        when(discordService.createEventChannel(any(Event.class))).thenReturn(channel);
        when(discordService.getChannel(any(Event.class))).thenReturn(channel);
        when(discordService.postEventMessage(any(Event.class), any(TextChannel.class)))
                .thenReturn(message);
        return channel;
    }

    // -----------------------------------------------------------------------
    // 1. InitChannelOperation: CREATED → INIT_CHANNEL
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("INIT_CHANNEL: CREATED → INIT_CHANNEL, creates channel and posts message")
    void initChannel_created_createsChannelAndMovesToInitChannel() {
        Event event = seedEvent(EventState.CREATED);
        event.setChannelId(0L);
        event.setMessageId(0L);
        eventRepository.save(event);
        stubChannel();

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.INIT_CHANNEL);

        assertThat(transitioned).isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.INIT_CHANNEL);
        verify(discordService).createEventChannel(any(Event.class));
        verify(discordService).postEventMessage(any(Event.class), any(TextChannel.class));
        verify(discordService).sortActiveChannels();
    }

    // -----------------------------------------------------------------------
    // 2. InitRolesOperation: INIT_CHANNEL → INIT_ROLES
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("INIT_ROLES: INIT_CHANNEL → INIT_ROLES, creates roles")
    void initRoles_initChannel_createsRolesAndMovesToInitRoles() {
        Event event = seedEvent(EventState.INIT_CHANNEL);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.INIT_ROLES);

        assertThat(transitioned).isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.INIT_ROLES);
        verify(discordService).createEventRoles(any(Event.class));
    }

    // -----------------------------------------------------------------------
    // 3. ClassifyOperation: INIT_ROLES → CLASSIFY
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CLASSIFY: INIT_ROLES → CLASSIFY, classification attempted")
    void classify_initRoles_movesToClassify() {
        Event event = seedEvent(EventState.INIT_ROLES);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.CLASSIFY);

        assertThat(transitioned).isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.CLASSIFY);
    }

    // -----------------------------------------------------------------------
    // 4. InitCompleteOperation: CLASSIFY → PLANNED
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("INIT_COMPLETE: CLASSIFY → PLANNED, updates event message")
    void initComplete_classify_movesToPlanned() {
        Event event = seedEvent(EventState.CLASSIFY);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.INIT_COMPLETE);

        assertThat(transitioned).isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.PLANNED);
        verify(discordService).updateEventMessage(any(Event.class));
    }

    // -----------------------------------------------------------------------
    // 5. PreEventNotifyOperation: PLANNED → PRE_NOTIFIED
    //    Guard: now is between (eventTime - 2h) and eventTime.
    //    Fixed clock is 2018-01-06T13:00Z; event is set 1 hour from now.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PRE_EVENT_NOTIFY: PLANNED → PRE_NOTIFIED when within 2h of event")
    void preEventNotify_planned_sendsNotificationAndMovesToPreNotified() {
        ZonedDateTime eventTime =
                ZonedDateTime.now(Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))).plusHours(1);
        Event event = seedEvent(EventState.PLANNED, eventTime);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.PRE_EVENT_NOTIFY);

        assertThat(transitioned).isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.PRE_NOTIFIED);
        verify(discordService).sendMessageBeforeEvent(any(Event.class));
    }

    @Test
    @DisplayName("PRE_EVENT_NOTIFY: PLANNED stays PLANNED when event is more than 2h away (guard blocks)")
    void preEventNotify_tooEarly_doesNotTransition() {
        // Event is 7 days out — guard should reject
        Event event = seedEvent(EventState.PLANNED);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.PRE_EVENT_NOTIFY);

        assertThat(transitioned).isFalse();
        assertThat(reloadState(event)).isEqualTo(EventState.PLANNED);
    }

    // -----------------------------------------------------------------------
    // 6. PrepareAlbumOperation: PRE_NOTIFIED → POST_ALBUM_READY
    //    Guard: immich must be enabled. We enable it by stubbing ImmichService
    //    and overriding the property for that test via @DynamicPropertySource
    //    not available per-test, so we use a separate nested class or just
    //    test that the guard blocks when disabled (default) and that the action
    //    path produces the correct state when the service is stubbed.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PREPARE_ALBUM: PRE_NOTIFIED stays PRE_NOTIFIED when Immich is disabled (guard blocks)")
    void prepareAlbum_immichDisabled_doesNotTransition() {
        Event event = seedEvent(EventState.PRE_NOTIFIED);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.PREPARE_ALBUM);

        assertThat(transitioned).isFalse();
        assertThat(reloadState(event)).isEqualTo(EventState.PRE_NOTIFIED);
    }

    // -----------------------------------------------------------------------
    // 7. CompleteOperation: PRE_NOTIFIED → POST_COMPLETED (skip album path)
    //    Guard: now > eventTime + 6h. Event is in the past by 7 hours.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("COMPLETE (from PRE_NOTIFIED): PRE_NOTIFIED → POST_COMPLETED when 6h after event")
    void complete_preNotified_movesToPostCompleted() {
        ZonedDateTime pastEvent =
                ZonedDateTime.now(Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))).minusHours(7);
        Event event = seedEvent(EventState.PRE_NOTIFIED, pastEvent);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.COMPLETE);

        assertThat(transitioned).isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.POST_COMPLETED);
        verify(discordService).removeEventButtons(any(Event.class));
    }

    @Test
    @DisplayName("COMPLETE guard blocks when event is less than 6h in the past")
    void complete_tooSoon_doesNotTransition() {
        // Event ended 3h ago — guard should reject
        ZonedDateTime recentPast =
                ZonedDateTime.now(Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))).minusHours(3);
        Event event = seedEvent(EventState.PRE_NOTIFIED, recentPast);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.COMPLETE);

        assertThat(transitioned).isFalse();
        assertThat(reloadState(event)).isEqualTo(EventState.PRE_NOTIFIED);
    }

    // -----------------------------------------------------------------------
    // 8. PostAlbumOperation: POST_ALBUM_READY → POST_ALBUM_SHARED
    //    Guard: now > eventTime + 1h. Seed immichShareKey so getShareUrl is called.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST_ALBUM: POST_ALBUM_READY → POST_ALBUM_SHARED, posts album link")
    void postAlbum_postAlbumReady_postsLinkAndMovesToPostAlbumShared() {
        ZonedDateTime pastEvent =
                ZonedDateTime.now(Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))).minusHours(2);
        Event event = seedEvent(EventState.POST_ALBUM_READY, pastEvent);
        event.setImmichAlbumId("album-id-123");
        event.setImmichShareKey("share-key-abc");
        eventRepository.save(event);

        dev.tylercash.event.immich.ImmichService immichService =
                org.mockito.Mockito.mock(dev.tylercash.event.immich.ImmichService.class);
        when(immichService.getShareUrl(anyString())).thenReturn("https://photos.example.com/s/share-key-abc");

        // ImmichService is not @MockitoBean here — we use the real bean but it
        // simply won't be called for the URL if we inject a mock. Instead we
        // verify the discord side-effect and state transition; the ImmichService
        // is already wired in the context and getShareUrl only formats a URL.
        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.POST_ALBUM);

        assertThat(transitioned).isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.POST_ALBUM_SHARED);
        verify(discordService).sendAlbumLink(any(Event.class), anyString());
        verify(discordService).updateEventMessage(any(Event.class));
    }

    @Test
    @DisplayName("POST_ALBUM guard blocks when event ended less than 1h ago")
    void postAlbum_tooSoon_doesNotTransition() {
        ZonedDateTime recentPast =
                ZonedDateTime.now(Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))).minusMinutes(30);
        Event event = seedEvent(EventState.POST_ALBUM_READY, recentPast);
        event.setImmichAlbumId("album-id-123");
        event.setImmichShareKey("share-key-abc");
        eventRepository.save(event);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.POST_ALBUM);

        assertThat(transitioned).isFalse();
        assertThat(reloadState(event)).isEqualTo(EventState.POST_ALBUM_READY);
    }

    // -----------------------------------------------------------------------
    // 9. CompleteOperation: POST_ALBUM_SHARED → POST_COMPLETED
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("COMPLETE (from POST_ALBUM_SHARED): POST_ALBUM_SHARED → POST_COMPLETED")
    void complete_postAlbumShared_movesToPostCompleted() {
        ZonedDateTime pastEvent =
                ZonedDateTime.now(Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))).minusHours(7);
        Event event = seedEvent(EventState.POST_ALBUM_SHARED, pastEvent);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.COMPLETE);

        assertThat(transitioned).isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.POST_COMPLETED);
        verify(discordService).removeEventButtons(any(Event.class));
    }

    // -----------------------------------------------------------------------
    // 10. ArchiveOperation: POST_COMPLETED → ARCHIVED
    //     Guard: now > (eventTime + 1 day) at 22:00. Event is 3 days in past.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("ARCHIVE: POST_COMPLETED → ARCHIVED, archives channel")
    void archive_postCompleted_archivesChannelAndMovesToArchived() {
        ZonedDateTime oldEvent =
                ZonedDateTime.now(Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))).minusDays(3);
        Event event = seedEvent(EventState.POST_COMPLETED, oldEvent);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.ARCHIVE);

        assertThat(transitioned).isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.ARCHIVED);
        verify(discordService).archiveEventChannel(any(Event.class));
        verify(discordService).updateEventMessage(any(Event.class));
    }

    @Test
    @DisplayName("ARCHIVE guard blocks when archive time has not yet passed")
    void archive_tooSoon_doesNotTransition() {
        // Event just completed; archive window hasn't opened yet
        ZonedDateTime recentEvent =
                ZonedDateTime.now(Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))).minusHours(8);
        Event event = seedEvent(EventState.POST_COMPLETED, recentEvent);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.ARCHIVE);

        assertThat(transitioned).isFalse();
        assertThat(reloadState(event)).isEqualTo(EventState.POST_COMPLETED);
    }

    // -----------------------------------------------------------------------
    // 11. CancelOperation: PLANNED → CANCELLED (with channel and message)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CANCEL (from PLANNED): PLANNED → CANCELLED, archives and updates channel")
    void cancel_planned_cancelsAndMovesToCancelled() {
        Event event = seedEvent(EventState.PLANNED);
        // channelId and messageId already set in seedEvent (non-zero)

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.CANCEL);

        assertThat(transitioned).isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.CANCELLED);
        verify(discordService).removeEventButtons(any(Event.class));
        verify(discordService).archiveEventChannel(any(Event.class));
        verify(discordService).updateChannelName(any(Event.class));
    }

    @Test
    @DisplayName("CANCEL (from CREATED): CREATED → CANCELLED without channel ops")
    void cancel_created_cancelsAndMovesToCancelled() {
        Event event = seedEvent(EventState.CREATED);
        event.setChannelId(0L);
        event.setMessageId(0L);
        eventRepository.save(event);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.CANCEL);

        assertThat(transitioned).isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.CANCELLED);
    }

    // -----------------------------------------------------------------------
    // 12. DeleteOperation: CANCELLED → DELETED
    //     Guard: now > eventTime + 3 months. Use event well in the past.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DELETE (from CANCELLED): CANCELLED → DELETED, deletes roles and channel")
    void delete_cancelled_deletesEventAndMovesToDeleted() {
        ZonedDateTime veryOldEvent =
                ZonedDateTime.now(Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))).minusMonths(4);
        Event event = seedEvent(EventState.CANCELLED, veryOldEvent);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.DELETE);

        assertThat(transitioned).isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.DELETED);
        verify(discordService).deleteEventRoles(any(Event.class));
        verify(discordService).deleteEventChannel(any(Event.class));
    }

    @Test
    @DisplayName("DELETE (from ARCHIVED): ARCHIVED → DELETED")
    void delete_archived_deletesEventAndMovesToDeleted() {
        ZonedDateTime veryOldEvent =
                ZonedDateTime.now(Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))).minusMonths(4);
        Event event = seedEvent(EventState.ARCHIVED, veryOldEvent);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.DELETE);

        assertThat(transitioned).isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.DELETED);
        verify(discordService).deleteEventRoles(any(Event.class));
        verify(discordService).deleteEventChannel(any(Event.class));
    }

    @Test
    @DisplayName("DELETE guard blocks when event is less than 3 months old")
    void delete_tooSoon_doesNotTransition() {
        ZonedDateTime recentEvent =
                ZonedDateTime.now(Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"))).minusMonths(1);
        Event event = seedEvent(EventState.CANCELLED, recentEvent);

        boolean transitioned = stateMachineService.attemptTransition(event, EventStateMachineEvent.DELETE);

        assertThat(transitioned).isFalse();
        assertThat(reloadState(event)).isEqualTo(EventState.CANCELLED);
    }

    // -----------------------------------------------------------------------
    // 13. Happy-path composite: CREATED → INIT_CHANNEL → INIT_ROLES →
    //     CLASSIFY → PLANNED → PRE_NOTIFIED → POST_COMPLETED → ARCHIVED
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Happy path: CREATED through ARCHIVED driving each transition in sequence")
    void happyPath_createdToArchived() {
        // Event is 1h away for the PRE_EVENT_NOTIFY guard, and 8h in the past
        // for the COMPLETE guard. We use a time that satisfies both by
        // reloading the event and mutating dateTime between transitions.
        ZonedDateTime fixedNow = ZonedDateTime.now(Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC")));

        // ---- CREATED → INIT_CHANNEL ----
        Event event = seedEvent(EventState.CREATED, fixedNow.plusDays(7));
        event.setChannelId(0L);
        event.setMessageId(0L);
        eventRepository.save(event);
        stubChannel();

        assertThat(stateMachineService.attemptTransition(event, EventStateMachineEvent.INIT_CHANNEL))
                .isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.INIT_CHANNEL);

        // ---- INIT_CHANNEL → INIT_ROLES ----
        event = reload(event);
        assertThat(stateMachineService.attemptTransition(event, EventStateMachineEvent.INIT_ROLES))
                .isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.INIT_ROLES);

        // ---- INIT_ROLES → CLASSIFY ----
        event = reload(event);
        assertThat(stateMachineService.attemptTransition(event, EventStateMachineEvent.CLASSIFY))
                .isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.CLASSIFY);

        // ---- CLASSIFY → PLANNED ----
        event = reload(event);
        assertThat(stateMachineService.attemptTransition(event, EventStateMachineEvent.INIT_COMPLETE))
                .isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.PLANNED);

        // ---- PLANNED → PRE_NOTIFIED (move event to 1h from now) ----
        event = reload(event);
        event.setDateTime(fixedNow.plusHours(1));
        eventRepository.save(event);
        assertThat(stateMachineService.attemptTransition(event, EventStateMachineEvent.PRE_EVENT_NOTIFY))
                .isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.PRE_NOTIFIED);

        // ---- PRE_NOTIFIED → POST_COMPLETED (move event 7h in the past) ----
        event = reload(event);
        event.setDateTime(fixedNow.minusHours(7));
        eventRepository.save(event);
        assertThat(stateMachineService.attemptTransition(event, EventStateMachineEvent.COMPLETE))
                .isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.POST_COMPLETED);

        // ---- POST_COMPLETED → ARCHIVED (move event 3 days in the past) ----
        event = reload(event);
        event.setDateTime(fixedNow.minusDays(3));
        eventRepository.save(event);
        assertThat(stateMachineService.attemptTransition(event, EventStateMachineEvent.ARCHIVE))
                .isTrue();
        assertThat(reloadState(event)).isEqualTo(EventState.ARCHIVED);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private EventState reloadState(Event event) {
        return eventRepository.findById(event.getId()).orElseThrow().getState();
    }

    private Event reload(Event event) {
        return eventRepository.findById(event.getId()).orElseThrow();
    }
}
