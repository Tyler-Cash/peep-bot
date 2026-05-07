package dev.tylercash.event.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventState;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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
class ListenerInvocationRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ListenerInvocationRepository listenerInvocationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private static final AtomicLong messageIdCounter = new AtomicLong(20_000);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM listener_invocation");
        jdbc.execute("DELETE FROM event_category");
        jdbc.execute("DELETE FROM attendance");
        jdbc.execute("DELETE FROM event");
    }

    private UUID createEvent() {
        long id = messageIdCounter.incrementAndGet();
        Event event = new Event(
                id, 111L, id, "Test Event", "creator", ZonedDateTime.now().plusDays(1), "desc");
        event.setState(EventState.PLANNED);
        return eventRepository.save(event).getId();
    }

    private ListenerInvocation invocation(
            UUID eventId, String type, String listenerName, ListenerInvocationStatus status, Instant nextRetryAt) {
        ListenerInvocation inv = new ListenerInvocation();
        inv.setEventId(eventId);
        inv.setLifecycleEventType(type);
        inv.setListenerName(listenerName);
        inv.setStatus(status);
        inv.setAttempts(1);
        inv.setNextRetryAt(nextRetryAt);
        return inv;
    }

    @Test
    void findDueForRetry_returnsPendingAndFailedDueRows_excludesSuccessAndFutureRows() {
        UUID eventId = createEvent();
        Instant now = Instant.now();

        // Row 1: PENDING with no nextRetryAt — should be returned
        ListenerInvocation pending =
                invocation(eventId, "EventCreated", "PendingListener", ListenerInvocationStatus.PENDING, null);

        // Row 2: FAILED with nextRetryAt in the past — should be returned
        ListenerInvocation failedDue = invocation(
                eventId, "EventCreated", "FailedDueListener", ListenerInvocationStatus.FAILED, now.minusSeconds(10));

        // Row 3: FAILED with nextRetryAt in the future — should NOT be returned
        ListenerInvocation failedFuture = invocation(
                eventId, "EventCreated", "FailedFutureListener", ListenerInvocationStatus.FAILED, now.plusSeconds(60));

        // Row 4: SUCCESS — should NOT be returned
        ListenerInvocation success =
                invocation(eventId, "EventCreated", "SuccessListener", ListenerInvocationStatus.SUCCESS, null);

        listenerInvocationRepository.saveAll(List.of(pending, failedDue, failedFuture, success));

        List<ListenerInvocation> results = listenerInvocationRepository.findDueForRetry(now, PageRequest.of(0, 10));

        assertThat(results).hasSize(2);
        assertThat(results)
                .extracting(ListenerInvocation::getListenerName)
                .containsExactlyInAnyOrder("PendingListener", "FailedDueListener");
    }
}
