package dev.tylercash.event.event;

import dev.tylercash.event.TylerBotApplication;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.TestcontainersConfiguration;

import java.time.ZonedDateTime;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(classes = TylerBotApplication.class)
@Testcontainers
@ActiveProfiles("local")
class EventServiceIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer();
    @Autowired
    private EventService eventService;
    @Autowired
    private DiscordService discordService;

    @BeforeAll
    static void beforeAll() {
        postgres.start();
    }

    @AfterAll
    static void afterAll() {
        postgres.stop();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    public void testSave() throws InterruptedException {
        Event event = new Event(0, 0, 0, "name", "description", ZonedDateTime.now());
        eventService.createEvent(event);
        discordService.deleteEventChannel(event);
        // Terrible way to have the channel deleted by the time the test is shutdown
        Thread.sleep(5000);
    }
}