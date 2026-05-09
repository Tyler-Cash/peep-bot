package dev.tylercash.event.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Asserts the F-004 security headers (Content-Security-Policy, Referrer-Policy,
 * Permissions-Policy) are emitted on backend responses by the configured
 * security filter chain.
 */
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
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
class SecurityHeadersIntegrationTest {

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void csrfEndpoint_emitsHardenedSecurityHeaders() throws Exception {
        MvcResult result = mockMvc.perform(get("/csrf")).andReturn();

        assertThat(result.getResponse().getHeader("Content-Security-Policy"))
                .as("CSP must lock default-src to self and forbid framing/base hijacking")
                .isEqualTo("default-src 'self'; frame-ancestors 'none'; base-uri 'none'");
        assertThat(result.getResponse().getHeader("Referrer-Policy"))
                .as("Referrer-Policy must be no-referrer to prevent URL leakage")
                .isEqualTo("no-referrer");
        assertThat(result.getResponse().getHeader("Permissions-Policy"))
                .as("Permissions-Policy must opt out of FLoC interest cohorts")
                .isEqualTo("interest-cohort=()");
    }
}
