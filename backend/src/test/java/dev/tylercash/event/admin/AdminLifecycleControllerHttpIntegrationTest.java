package dev.tylercash.event.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.TestIds;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Verifies the F-010 fix: {@link AdminLifecycleController#replay} no longer dispatches via
 * reflection. The exhaustive switch must reject unknown lifecycle event type strings with a 400 and
 * accept the well-known names (e.g. {@code EventCreated}).
 */
class AdminLifecycleControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    private static final String BOT_ADMIN = TestIds.nextSnowflake();

    @DynamicPropertySource
    static void registerBotAdmin(DynamicPropertyRegistry r) {
        r.add("dev.tylercash.bot-admins[0]", () -> BOT_ADMIN);
    }

    private long GUILD_A;

    @org.junit.jupiter.api.BeforeEach
    void allocateTestIds() {
        GUILD_A = TestIds.nextLong();
    }

    private void seedGuild(long guildId) {
        jdbc.execute("INSERT INTO guild (guild_id, events_role, organiser_role, emoji_accepted, emoji_declined,"
                + " emoji_maybe, joined_at, active, immich_enabled, google_autocomplete_enabled,"
                + " rewind_enabled)"
                + " VALUES ("
                + guildId
                + ", 'events', 'event-organiser', '✅', '❌', '❓', NOW(), true, false, false, false)"
                + " ON CONFLICT (guild_id) DO NOTHING");
    }

    @Test
    void botAdmin_replay_unknownLifecycleEventType_returns400() throws Exception {
        fixtures.registerMember(BOT_ADMIN, GUILD_A, "BotAdmin", "botadmin");
        seedGuild(GUILD_A);
        UUID eventId = fixtures.seedEvent(GUILD_A, BOT_ADMIN, "Test Event");

        String body = "{\"eventId\":\"" + eventId + "\",\"lifecycleEventType\":\"NotARealEvent\"}";

        mockMvc.perform(MockMvcRequestBuilders.post("/admin/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void botAdmin_replay_knownLifecycleEventType_publishes() throws Exception {
        fixtures.registerMember(BOT_ADMIN, GUILD_A, "BotAdmin", "botadmin");
        seedGuild(GUILD_A);
        UUID eventId = fixtures.seedEvent(GUILD_A, BOT_ADMIN, "Test Event");

        String body = "{\"eventId\":\"" + eventId + "\",\"lifecycleEventType\":\"EventCreated\"}";

        mockMvc.perform(MockMvcRequestBuilders.post("/admin/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authedAs(BOT_ADMIN))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.listeners").isArray());
    }
}
