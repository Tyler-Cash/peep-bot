package dev.tylercash.event.discord.listener;

import static dev.tylercash.event.discord.listener.ModalInteractionListener.PLUS_ONE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.AttendanceRecord;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalInteraction;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction;
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
            "dev.tylercash.discord.guild-id=0",
            "dev.tylercash.rate-limit.read-capacity=10000",
            "dev.tylercash.rate-limit.write-capacity=10000"
        })
@Testcontainers
@ActiveProfiles("local")
class ModalInteractionListenerIntegrationTest {

    private static final String OWNER_SNOWFLAKE = "444555666";
    private static final String OWNER_DISPLAY_NAME = "ModalUser";
    private static final String OWNER_USERNAME = "modaluser";
    private static final long SERVER_ID = 888L;

    private static final AtomicLong idCounter = new AtomicLong(60_000);

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");

    @Autowired
    ModalInteractionListener listener;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    AttendanceRepository attendanceRepository;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void truncate() {
        jdbc.execute("DELETE FROM attendance");
        jdbc.execute("DELETE FROM event_category");
        jdbc.execute("DELETE FROM event");
        jdbc.execute("DELETE FROM discord_user_guild");
        jdbc.execute("DELETE FROM discord_user_cache");
    }

    private Event seedOpenEvent() {
        long id = idCounter.incrementAndGet();
        Event event = new Event(
                id,
                SERVER_ID,
                id,
                "Modal Integration Event",
                OWNER_SNOWFLAKE,
                ZonedDateTime.now().plusDays(1),
                "desc");
        event.setState(EventState.PLANNED);
        return eventRepository.save(event);
    }

    private ModalInteractionEvent buildModalEvent(Event event, String guestName) {
        ModalInteractionEvent evt = mock(ModalInteractionEvent.class);

        ModalInteraction interaction = mock(ModalInteraction.class);
        when(evt.getInteraction()).thenReturn(interaction);
        when(interaction.getModalId()).thenReturn(PLUS_ONE_ID);

        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        when(channel.getIdLong()).thenReturn(event.getChannelId());
        when(evt.getChannel()).thenReturn(channel);

        User user = mock(User.class);
        when(user.getId()).thenReturn(OWNER_SNOWFLAKE);
        when(user.getName()).thenReturn(OWNER_USERNAME);
        when(user.getEffectiveName()).thenReturn(OWNER_DISPLAY_NAME);
        when(evt.getUser()).thenReturn(user);

        Member member = mock(Member.class);
        when(member.getNickname()).thenReturn(OWNER_DISPLAY_NAME);
        when(member.getEffectiveName()).thenReturn(OWNER_DISPLAY_NAME);
        when(evt.getMember()).thenReturn(member);

        if (guestName != null) {
            ModalMapping mapping = mock(ModalMapping.class);
            when(mapping.getAsString()).thenReturn(guestName);
            when(interaction.getValues()).thenReturn(List.of(mapping));
        }

        when(evt.editMessageEmbeds(any(List.class))).thenReturn(mock(MessageEditCallbackAction.class));
        when(evt.editMessageEmbeds(any(MessageEmbed[].class))).thenReturn(mock(MessageEditCallbackAction.class));

        return evt;
    }

    @SuppressWarnings("unchecked")
    private static <T> T any(Class<T> cls) {
        return org.mockito.ArgumentMatchers.any(cls);
    }

    private List<AttendanceRecord> latestAttendance(UUID eventId) {
        return attendanceRepository.findLatestPerAttendee(eventId);
    }

    @Test
    @DisplayName("+1 modal submission → attendance row with name=[+1] Guest, status=ACCEPTED, ownerSnowflake set")
    void plusOneModal_createsAcceptedRow() {
        Event event = seedOpenEvent();
        ModalInteractionEvent evt = buildModalEvent(event, "Alice");

        listener.onModalInteraction(evt);

        List<AttendanceRecord> rows = latestAttendance(event.getId());
        assertThat(rows).hasSize(1);
        AttendanceRecord row = rows.get(0);
        assertThat(row.getStatus()).isEqualTo(AttendanceStatus.ACCEPTED);
        assertThat(row.getName()).isEqualTo("[+1] Alice");
        assertThat(row.getSnowflake()).isNull();
        assertThat(row.getOwnerSnowflake()).isEqualTo(OWNER_SNOWFLAKE);
    }

    @Test
    @DisplayName("submitting the same +1 name twice → two rows (recordAttendance is not idempotent by design)")
    void plusOneModal_submittedTwice_createsTwoRows() {
        Event event = seedOpenEvent();

        listener.onModalInteraction(buildModalEvent(event, "Bob"));
        listener.onModalInteraction(buildModalEvent(event, "Bob"));

        // findLatestPerAttendee deduplicates on COALESCE(snowflake, name), so
        // repeated +1 with same name collapses to 1 current entry.
        List<AttendanceRecord> rows = latestAttendance(event.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getName()).isEqualTo("[+1] Bob");
        assertThat(rows.get(0).getStatus()).isEqualTo(AttendanceStatus.ACCEPTED);
    }

    @Test
    @DisplayName("two distinct +1 names → two independent attendance rows")
    void plusOneModal_twoDistinctGuests_createsTwoRows() {
        Event event = seedOpenEvent();

        listener.onModalInteraction(buildModalEvent(event, "Charlie"));
        listener.onModalInteraction(buildModalEvent(event, "Diana"));

        List<AttendanceRecord> rows = latestAttendance(event.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(AttendanceRecord::getStatus).containsOnly(AttendanceStatus.ACCEPTED);
    }

    @Test
    @DisplayName("unknown modal ID → no attendance row created")
    void unknownModalId_noDbChange() {
        Event event = seedOpenEvent();

        ModalInteractionEvent evt = mock(ModalInteractionEvent.class);
        ModalInteraction interaction = mock(ModalInteraction.class);
        when(evt.getInteraction()).thenReturn(interaction);
        when(interaction.getModalId()).thenReturn("some-other-modal");

        listener.onModalInteraction(evt);

        assertThat(latestAttendance(event.getId())).isEmpty();
    }
}
