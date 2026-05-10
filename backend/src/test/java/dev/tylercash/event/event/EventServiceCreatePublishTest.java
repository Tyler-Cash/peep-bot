package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.test.SharedPostgres;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.lifecycle.ListenerInvocation;
import dev.tylercash.event.lifecycle.ListenerInvocationRepository;
import dev.tylercash.event.lifecycle.ListenerInvocationStatus;
import dev.tylercash.event.lifecycle.PostCommitDispatcher;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.dv8tion.jda.api.JDA;
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
            "dev.tylercash.discord.guild-id=0"
        })
@ActiveProfiles("local")
class EventServiceCreatePublishTest {

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    // Prevent the post-commit dispatcher from actually invoking listeners so the
    // outbox rows remain in PENDING status for the assertion.
    @MockitoBean
    PostCommitDispatcher postCommitDispatcher;

    @Autowired
    private EventService eventService;

    @Autowired
    private ListenerInvocationRepository listenerInvocationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private static final AtomicLong idCounter = new AtomicLong(90_000);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.registerIsolatedDatabase(registry, EventServiceCreatePublishTest.class);
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM listener_invocation");
        jdbc.execute("DELETE FROM event_category");
        jdbc.execute("DELETE FROM attendance");
        jdbc.execute("DELETE FROM event");
    }

    @Test
    @DisplayName("createEvent publishes EventCreated and writes a PENDING outbox row for Discord Channel Init")
    void createEvent_writesEventCreatedOutboxRow() {
        long uniqueId = idCounter.incrementAndGet();
        Event event = new Event(
                uniqueId,
                111L,
                uniqueId,
                "Test Publish Event",
                "creator",
                ZonedDateTime.now().plusDays(1),
                "desc");

        eventService.createEvent(event);

        UUID eventId = event.getId();
        assertThat(eventId).isNotNull();

        List<ListenerInvocation> rows = listenerInvocationRepository.findAll();

        // Only DiscordChannelInitListener subscribes to EventCreated
        assertThat(rows).hasSize(1);

        ListenerInvocation row = rows.get(0);
        assertThat(row.getEventId()).isEqualTo(eventId);
        assertThat(row.getLifecycleEventType()).isEqualTo("EventCreated");
        assertThat(row.getListenerName()).isEqualTo("Discord Channel Init");
        assertThat(row.getStatus()).isEqualTo(ListenerInvocationStatus.PENDING);
    }
}
