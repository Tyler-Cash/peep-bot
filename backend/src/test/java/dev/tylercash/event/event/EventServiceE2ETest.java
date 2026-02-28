package dev.tylercash.event.event;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZonedDateTime;

/**
 * End-to-end tests that require real external services (Discord bot, PostgreSQL).
 * Requires DISCORD_TOKEN, DISCORD_GUILD_ID, and OAuth2 credentials to be set
 * in the environment — these are available in CI via GitHub Actions secrets but
 * not locally unless you have a real application-local.yaml configured.
 *
 * Run explicitly with: ./gradlew e2eTest
 */
@SpringBootTest(classes = PeepBotApplication.class)
@Testcontainers
@ActiveProfiles("local")
@Tag("e2e")
class EventServiceE2ETest {

    @Container
    public static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:13-alpine");

    @Autowired
    private EventService eventService;
    @Autowired
    private DiscordService discordService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    public void testSave() throws InterruptedException {
        Event event = new Event(0, 0, 0, "name", "creator", ZonedDateTime.now(), "description");
        eventService.createEvent(event);
        discordService.deleteEventChannel(event);
        // Terrible way to have the channel deleted by the time the test is shutdown
        Thread.sleep(5000);
    }
}
