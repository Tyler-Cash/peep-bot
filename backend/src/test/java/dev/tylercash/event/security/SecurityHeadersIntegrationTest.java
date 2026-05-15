package dev.tylercash.event.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.discord.DiscordInitializationService;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.test.SharedPostgres;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
@ActiveProfiles("local")
class SecurityHeadersIntegrationTest {

    @MockitoBean
    JDA jda;

    @MockitoBean
    DiscordService discordService;

    @MockitoBean
    DiscordInitializationService discordInitializationService;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.registerProperties(registry);
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
