package dev.tylercash.event.discord;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class GuildFeaturesControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    private static final String MEMBER = "601";
    private static final String NON_MEMBER = "602";
    private static final long GUILD_A = 5001L;
    private static final long GUILD_B = 5002L;

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
    void anonymous_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/guild/{guildId}/features", GUILD_A))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonMember_returns403() throws Exception {
        fixtures.registerMember(NON_MEMBER, GUILD_B, "Bob", "bob");

        mockMvc.perform(MockMvcRequestBuilders.get("/guild/{guildId}/features", GUILD_A)
                        .with(authedAs(NON_MEMBER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void member_returnsFeatureMap() throws Exception {
        fixtures.registerMember(MEMBER, GUILD_A, "Alice", "alice");
        seedGuild(GUILD_A);

        mockMvc.perform(MockMvcRequestBuilders.get("/guild/{guildId}/features", GUILD_A)
                        .with(authedAs(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.immichEnabled").value(false))
                .andExpect(jsonPath("$.googleAutocompleteEnabled").value(false))
                .andExpect(jsonPath("$.rewindEnabled").value(false));
    }
}
