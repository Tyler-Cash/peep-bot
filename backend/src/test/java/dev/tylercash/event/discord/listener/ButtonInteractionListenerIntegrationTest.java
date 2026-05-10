package dev.tylercash.event.discord.listener;

import static dev.tylercash.event.discord.listener.ButtonInteractionListener.ACCEPTED;
import static dev.tylercash.event.discord.listener.ButtonInteractionListener.DECLINED;
import static dev.tylercash.event.discord.listener.ButtonInteractionListener.MAYBE;
import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.event.model.AttendanceRecord;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import dev.tylercash.event.test.SharedPostgres;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ModalCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
            "dev.tylercash.discord.guild-id=0",
            "dev.tylercash.frontend.hostname=test.local",
            "dev.tylercash.rate-limit.read-capacity=10000",
            "dev.tylercash.rate-limit.write-capacity=10000"
        })
@ActiveProfiles("local")
class ButtonInteractionListenerIntegrationTest {

    private static final String USER_SNOWFLAKE = "111222333";
    private static final String USER_DISPLAY_NAME = "TestUser";
    private static final String USER_USERNAME = "testuser";
    private static final long SERVER_ID = 999L;

    private static final AtomicLong idCounter = new AtomicLong(50_000);

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @Autowired
    ButtonInteractionListener listener;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    AttendanceRepository attendanceRepository;

    @Autowired
    DiscordUserCacheService discordUserCacheService;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.registerProperties(registry);
    }

    @BeforeEach
    void truncate() {}

    private Event seedPlannedEvent() {
        long id = idCounter.incrementAndGet();
        Event event = new Event(
                id,
                SERVER_ID,
                id,
                "Integration Test Event",
                USER_SNOWFLAKE,
                ZonedDateTime.now().plusDays(1),
                "desc");
        event.setState(EventState.PLANNED);
        return eventRepository.save(event);
    }

    private ButtonInteractionEvent mockButtonEvent(Event event, String buttonId) {
        ButtonInteractionEvent evt = mock(ButtonInteractionEvent.class);

        Button button = mock(Button.class);
        when(button.getCustomId()).thenReturn(buttonId);
        when(evt.getButton()).thenReturn(button);
        when(evt.getMessageIdLong()).thenReturn(event.getMessageId());

        Member member = mock(Member.class);
        when(member.getId()).thenReturn(USER_SNOWFLAKE);
        when(member.getNickname()).thenReturn(USER_DISPLAY_NAME);
        when(member.getEffectiveName()).thenReturn(USER_DISPLAY_NAME);
        when(evt.getMember()).thenReturn(member);

        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_SNOWFLAKE);
        when(user.getName()).thenReturn(USER_USERNAME);
        when(evt.getUser()).thenReturn(user);

        when(evt.editMessageEmbeds(any(List.class))).thenReturn(mock(MessageEditCallbackAction.class));
        when(evt.editMessageEmbeds(any(MessageEmbed[].class))).thenReturn(mock(MessageEditCallbackAction.class));

        return evt;
    }

    private List<AttendanceRecord> latestAttendance(UUID eventId) {
        return attendanceRepository.findLatestPerAttendee(eventId);
    }

    @Test
    @DisplayName("accepted button → ACCEPTED attendance row in DB")
    void acceptedButton_createsAcceptedRow() {
        Event event = seedPlannedEvent();
        ButtonInteractionEvent evt = mockButtonEvent(event, ACCEPTED);

        listener.onButtonInteraction(evt);

        List<AttendanceRecord> rows = latestAttendance(event.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(AttendanceStatus.ACCEPTED);
        assertThat(rows.get(0).getSnowflake()).isEqualTo(USER_SNOWFLAKE);
    }

    @Test
    @DisplayName("declined button → DECLINED attendance row in DB")
    void declinedButton_createsDeclinedRow() {
        Event event = seedPlannedEvent();
        ButtonInteractionEvent evt = mockButtonEvent(event, DECLINED);

        listener.onButtonInteraction(evt);

        List<AttendanceRecord> rows = latestAttendance(event.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(AttendanceStatus.DECLINED);
    }

    @Test
    @DisplayName("maybe button → MAYBE attendance row in DB")
    void maybeButton_createsMaybeRow() {
        Event event = seedPlannedEvent();
        ButtonInteractionEvent evt = mockButtonEvent(event, MAYBE);

        listener.onButtonInteraction(evt);

        List<AttendanceRecord> rows = latestAttendance(event.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(AttendanceStatus.MAYBE);
    }

    @Test
    @DisplayName("accepting then declining → latest row is DECLINED (flip behaviour)")
    void switchingResponse_updatesToLatestStatus() {
        Event event = seedPlannedEvent();

        listener.onButtonInteraction(mockButtonEvent(event, ACCEPTED));
        listener.onButtonInteraction(mockButtonEvent(event, DECLINED));

        List<AttendanceRecord> rows = latestAttendance(event.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(AttendanceStatus.DECLINED);
    }

    @Test
    @DisplayName("pressing same button twice → status REMOVED (toggle off)")
    void pressingTwice_removesAttendance() {
        Event event = seedPlannedEvent();

        listener.onButtonInteraction(mockButtonEvent(event, ACCEPTED));
        listener.onButtonInteraction(mockButtonEvent(event, ACCEPTED));

        List<AttendanceRecord> rows = latestAttendance(event.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(AttendanceStatus.REMOVED);
    }

    @Test
    @DisplayName("unknown message ID → no DB changes")
    void unknownMessageId_noDbChange() {
        Event event = seedPlannedEvent();

        ButtonInteractionEvent evt = mock(ButtonInteractionEvent.class);
        Button button = mock(Button.class);
        when(button.getCustomId()).thenReturn(ACCEPTED);
        when(evt.getButton()).thenReturn(button);
        // message ID that doesn't match any event
        when(evt.getMessageIdLong()).thenReturn(99999999L);

        listener.onButtonInteraction(evt);

        assertThat(latestAttendance(event.getId())).isEmpty();
    }

    @Test
    @DisplayName("plus-one button → replyModal called, no attendance row created")
    void plusOneButton_opensModal() {
        Event event = seedPlannedEvent();
        ButtonInteractionEvent evt = mock(ButtonInteractionEvent.class);

        Button button = mock(Button.class);
        when(button.getCustomId()).thenReturn(PLUS_ONE_ID);
        when(evt.getButton()).thenReturn(button);
        when(evt.getMessageIdLong()).thenReturn(event.getMessageId());
        when(evt.replyModal(any())).thenReturn(mock(ModalCallbackAction.class));

        // getMember needed by isCompleted path only after null check — event service
        // checks isCompleted before calling getMember for +1 path, so set it up
        Member member = mock(Member.class);
        when(member.getId()).thenReturn(USER_SNOWFLAKE);
        when(evt.getMember()).thenReturn(member);

        listener.onButtonInteraction(evt);

        verify(evt).replyModal(any());
        assertThat(latestAttendance(event.getId())).isEmpty();
    }

    @Test
    @DisplayName("completed event → ephemeral reply, no attendance row created")
    void completedEvent_repliesLocked() {
        long id = idCounter.incrementAndGet();
        Event event = new Event(
                id,
                SERVER_ID,
                id,
                "Done Event",
                USER_SNOWFLAKE,
                ZonedDateTime.now().minusDays(2),
                "desc");
        event.setState(EventState.POST_COMPLETED);
        event = eventRepository.save(event);

        ButtonInteractionEvent evt = mock(ButtonInteractionEvent.class);
        Button button = mock(Button.class);
        when(button.getCustomId()).thenReturn(ACCEPTED);
        when(evt.getButton()).thenReturn(button);
        when(evt.getMessageIdLong()).thenReturn(event.getMessageId());

        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(evt.reply(any(String.class))).thenReturn(reply);
        when(reply.setEphemeral(true)).thenReturn(reply);

        listener.onButtonInteraction(evt);

        verify(evt).reply("Attendance is locked for this event.");
        assertThat(latestAttendance(event.getId())).isEmpty();
    }
}
