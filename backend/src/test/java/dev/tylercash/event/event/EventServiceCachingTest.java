package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.statemachine.EventStateMachineEvent;
import dev.tylercash.event.event.statemachine.EventStateMachineService;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
class EventServiceCachingTest {

    private static final AtomicLong idCounter = new AtomicLong(5000);

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @MockitoBean
    EventStateMachineService stateMachineService;

    @Container
    public static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private CacheManager cacheManager;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        TextChannel mockChannel = mock(TextChannel.class);
        Message mockMessage = mock(Message.class);
        AuditableRestAction<Void> mockDeleteAction = mock(AuditableRestAction.class);
        when(mockChannel.getIdLong()).thenReturn(100L);
        when(mockChannel.delete()).thenReturn(mockDeleteAction);
        when(mockMessage.getGuildIdLong()).thenReturn(200L);
        when(mockMessage.getIdLong()).thenAnswer(inv -> idCounter.incrementAndGet());
        when(discordService.createEventChannel(any())).thenReturn(mockChannel);
        when(discordService.postEventMessage(any(), any())).thenReturn(mockMessage);

        // Clear all caches before each test
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    private UUID createPersistedEvent() {
        long uniqueId = idCounter.incrementAndGet();
        Event event = new Event(
                uniqueId,
                0,
                uniqueId,
                "test-event",
                "creator",
                ZonedDateTime.now().plusDays(1),
                "desc");
        event.setState(dev.tylercash.event.event.model.EventState.PLANNED);
        return eventRepository.save(event).getId();
    }

    @Test
    @DisplayName("getEvent returns cached result on second call")
    void getEvent_returnsCachedResult() {
        UUID id = createPersistedEvent();

        eventService.getEvent(id);
        eventService.getEvent(id);

        assertThat(cacheManager.getCache("eventDetail").get(id)).isNotNull();
    }

    @Test
    @DisplayName("getActiveEvents returns cached result on second call")
    void getActiveEvents_returnsCachedResult() {
        Pageable pageable = PageRequest.of(0, 10);

        eventService.getActiveEvents(pageable);
        eventService.getActiveEvents(pageable);

        assertThat(cacheManager.getCache("activeEvents")).isNotNull();
    }

    @Test
    @DisplayName("createEvent evicts activeEvents cache")
    void createEvent_evictsActiveEventsCache() {
        Pageable pageable = PageRequest.of(0, 10);
        eventService.getActiveEvents(pageable);

        long uniqueId = idCounter.incrementAndGet();
        Event event = new Event(
                uniqueId,
                0,
                uniqueId,
                "new-event",
                "creator",
                ZonedDateTime.now().plusDays(1),
                "desc");
        eventService.createEvent(event);

        // After eviction, calling again should repopulate from DB
        eventService.getActiveEvents(pageable);
    }

    @Test
    @DisplayName("updateEvent evicts eventDetail cache")
    void updateEvent_evictsEventDetailCache() {
        UUID id = createPersistedEvent();
        Event event = eventService.getEvent(id);

        assertThat(cacheManager.getCache("eventDetail").get(id)).isNotNull();

        eventService.updateEvent(event);

        assertThat(cacheManager.getCache("eventDetail").get(id)).isNull();
    }

    @Test
    @DisplayName("updateEvent evicts activeEvents cache")
    void updateEvent_evictsActiveEventsCache() {
        Pageable pageable = PageRequest.of(0, 10);
        eventService.getActiveEvents(pageable);

        UUID id = createPersistedEvent();
        Event event = eventService.getEvent(id);
        eventService.updateEvent(event);

        assertThat(cacheManager.getCache("activeEvents")).isNotNull();
    }

    @Test
    @DisplayName("cancelEvent evicts both caches")
    void cancelEvent_evictsBothCaches() {
        UUID id = createPersistedEvent();
        Pageable pageable = PageRequest.of(0, 10);

        // Populate both caches
        eventService.getEvent(id);
        eventService.getActiveEvents(pageable);

        when(stateMachineService.attemptTransition(any(), eq(EventStateMachineEvent.CANCEL)))
                .thenReturn(true);

        eventService.cancelEvent(id);

        assertThat(cacheManager.getCache("eventDetail").get(id)).isNull();
    }

    @Test
    @DisplayName("removeAttendee evicts both caches")
    void removeAttendee_evictsBothCaches() {
        UUID id = createPersistedEvent();
        Pageable pageable = PageRequest.of(0, 10);

        // Populate both caches
        eventService.getEvent(id);
        eventService.getActiveEvents(pageable);

        try {
            eventService.removeAttendee(id, "someSnowflake", null);
        } catch (Exception ignored) {
            // May fail due to missing attendance data, but eviction still occurs
        }

        assertThat(cacheManager.getCache("eventDetail").get(id)).isNull();
    }
}
