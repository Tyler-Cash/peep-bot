package dev.tylercash.event.event;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZonedDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = PeepBotApplication.class, properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.security.oauth2.client.registration.discord.client-id=test",
        "spring.security.oauth2.client.registration.discord.client-secret=test",
        "dev.tylercash.discord.token=dummy",
        "dev.tylercash.discord.guild-id=0"
})
@Testcontainers
@ActiveProfiles("local")
class EventServiceIntegrationTest {

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @Container
    public static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:13-alpine");

    @Autowired
    private EventService eventService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        TextChannel mockChannel = mock(TextChannel.class);
        Message mockMessage = mock(Message.class);
        when(mockChannel.getIdLong()).thenReturn(100L);
        when(mockMessage.getGuildIdLong()).thenReturn(200L);
        when(mockMessage.getIdLong()).thenReturn(300L);
        when(discordService.createEventChannel(any())).thenReturn(mockChannel);
        when(discordService.postEventMessage(any(), any())).thenReturn(mockMessage);
    }

    @Test
    public void testSave() {
        Event event = new Event(0, 0, 0, "name", "creator", ZonedDateTime.now(), "description");
        eventService.createEvent(event);
    }
}
