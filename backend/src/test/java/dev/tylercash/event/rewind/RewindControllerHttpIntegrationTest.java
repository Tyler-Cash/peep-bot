package dev.tylercash.event.rewind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.tylercash.event.rewind.model.RewindStatsDto;
import dev.tylercash.event.test.TestIds;
import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class RewindControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    private String MEMBER;
    private String NON_MEMBER;
    private long GUILD_A;
    private long GUILD_B;

    @org.junit.jupiter.api.BeforeEach
    void allocateTestIds() {
        MEMBER = TestIds.nextSnowflake();
        NON_MEMBER = TestIds.nextSnowflake();
        GUILD_A = TestIds.nextLong();
        GUILD_B = TestIds.nextLong();
    }
    private void seedGuild(long guildId, boolean rewindEnabled) {
        jdbc.execute("INSERT INTO guild (guild_id, events_role, organiser_role, emoji_accepted, emoji_declined,"
                + " emoji_maybe, joined_at, active, immich_enabled, google_autocomplete_enabled,"
                + " rewind_enabled)"
                + " VALUES ("
                + guildId
                + ", 'events', 'event-organiser', '✅', '❌', '❓', NOW(), true, false, false, "
                + rewindEnabled
                + ")"
                + " ON CONFLICT (guild_id) DO UPDATE SET rewind_enabled = EXCLUDED.rewind_enabled");
    }

    @Test
    void anonymous_get_rewind_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/rewind").param("guildId", String.valueOf(GUILD_A)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymous_get_rewindMe_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/rewind/me").param("guildId", String.valueOf(GUILD_A)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymous_get_years_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/rewind/years").param("guildId", String.valueOf(GUILD_A)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonMember_get_rewind_returns403() throws Exception {
        fixtures.registerMember(NON_MEMBER, GUILD_B, "Bob", "bob");

        mockMvc.perform(MockMvcRequestBuilders.get("/rewind")
                        .param("guildId", String.valueOf(GUILD_A))
                        .with(authedAs(NON_MEMBER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void member_rewindFeatureOff_returns403() throws Exception {
        fixtures.registerMember(MEMBER, GUILD_A, "Alice", "alice");
        // No guild row → rewind_enabled defaults to false in FeatureFlagService
        mockMvc.perform(MockMvcRequestBuilders.get("/rewind")
                        .param("guildId", String.valueOf(GUILD_A))
                        .with(authedAs(MEMBER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void member_get_rewind_returnsStats() throws Exception {
        fixtures.registerMember(MEMBER, GUILD_A, "Alice", "alice");
        seedGuild(GUILD_A, true);
        fixtures.seedEvent(GUILD_A, MEMBER, "Test Event");

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/rewind")
                        .param("guildId", String.valueOf(GUILD_A))
                        .with(authedAs(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").exists())
                .andReturn();

        RewindStatsDto dto = objectMapper.readValue(result.getResponse().getContentAsString(), RewindStatsDto.class);
        assertThat(dto.totalEvents()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void member_get_rewindMe_returnsStats() throws Exception {
        fixtures.registerMember(MEMBER, GUILD_A, "Alice", "alice");
        seedGuild(GUILD_A, true);
        fixtures.seedEvent(GUILD_A, MEMBER, "Test Event");

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/rewind/me")
                        .param("guildId", String.valueOf(GUILD_A))
                        .with(authedAs(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").exists())
                .andReturn();

        RewindStatsDto dto = objectMapper.readValue(result.getResponse().getContentAsString(), RewindStatsDto.class);
        assertThat(dto.totalEvents()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void member_get_years_returnsYearsArray() throws Exception {
        fixtures.registerMember(MEMBER, GUILD_A, "Alice", "alice");
        seedGuild(GUILD_A, true);
        fixtures.seedEvent(GUILD_A, MEMBER, "Test Event");

        int currentYear = ZonedDateTime.now().getYear();

        mockMvc.perform(MockMvcRequestBuilders.get("/rewind/years")
                        .param("guildId", String.valueOf(GUILD_A))
                        .with(authedAs(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasItem(currentYear)));
    }
}
