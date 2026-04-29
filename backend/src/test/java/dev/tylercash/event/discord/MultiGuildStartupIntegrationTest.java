package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tylercash.event.PeepBotApplication;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class MultiGuildStartupIntegrationTest {

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @Container
    public static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");

    @Autowired
    private GuildRepository guildRepository;

    @Autowired
    private GuildRegistrationService guildRegistrationService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void setUp() {
        when(jda.getGuilds()).thenReturn(List.of());
    }

    @Test
    void onboardingTwoGuildsCreatesTwoActiveRows() {
        guildRepository.deleteAll();

        net.dv8tion.jda.api.entities.Guild g1 = mock(net.dv8tion.jda.api.entities.Guild.class);
        when(g1.getIdLong()).thenReturn(1L);
        when(g1.getName()).thenReturn("Alpha");
        when(g1.getEmojisByName(anyString(), eq(true))).thenReturn(List.of());

        net.dv8tion.jda.api.entities.Guild g2 = mock(net.dv8tion.jda.api.entities.Guild.class);
        when(g2.getIdLong()).thenReturn(2L);
        when(g2.getName()).thenReturn("Beta");
        when(g2.getEmojisByName(anyString(), eq(true))).thenReturn(List.of());

        guildRegistrationService.onboard(g1);
        guildRegistrationService.onboard(g2);

        assertThat(guildRepository.findAllByActiveTrue())
                .extracting(Guild::getGuildId)
                .containsExactlyInAnyOrder(1L, 2L);

        assertThat(guildRepository.findById(1L)).isPresent().hasValueSatisfying(g -> {
            assertThat(g.getEventsRole()).isEqualTo("events");
            assertThat(g.getAdminRole()).isEqualTo("event-admin");
            assertThat(g.isActive()).isTrue();
        });

        assertThat(guildRepository.findById(2L)).isPresent().hasValueSatisfying(g -> {
            assertThat(g.getEventsRole()).isEqualTo("events");
            assertThat(g.getAdminRole()).isEqualTo("event-admin");
            assertThat(g.isActive()).isTrue();
        });
    }
}
