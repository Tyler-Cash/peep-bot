package dev.tylercash.event.discord;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.SharedPostgres;
import dev.tylercash.event.test.TestIds;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class GuildControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    // Dedicated context (different datasource URL) so the @MockitoBean jda + DB rows
    // can't be polluted by sibling HTTP test classes sharing the parent context.
    @DynamicPropertySource
    static void datasourceOverride(DynamicPropertyRegistry r) {
        SharedPostgres.registerIsolatedDatabase(r, GuildControllerHttpIntegrationTest.class);
    }

    @Test
    void anonymous_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/guild")).andExpect(status().isUnauthorized());
    }

    @Test
    void userInOneGuild_returnsThatGuild() throws Exception {
        String snowflake = TestIds.nextSnowflake();
        long guildId = TestIds.nextLong();
        fixtures.registerMember(snowflake, guildId, "Alice", "alice");

        Guild g = mock(Guild.class);
        when(g.getId()).thenReturn(String.valueOf(guildId));
        when(g.getName()).thenReturn("Guild One");
        when(g.getIconUrl()).thenReturn(null);
        when(g.getMemberCount()).thenReturn(10);
        when(jda.getGuildById(guildId)).thenReturn(g);

        mockMvc.perform(MockMvcRequestBuilders.get("/guild").with(authedAs(snowflake)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(String.valueOf(guildId)))
                .andExpect(jsonPath("$[0].name").value("Guild One"));
    }

    @Test
    void userInMultipleGuilds_returnsAll() throws Exception {
        String snowflake = TestIds.nextSnowflake();
        long guild1 = TestIds.nextLong();
        long guild2 = TestIds.nextLong();
        fixtures.registerMember(snowflake, guild1, "Alice", "alice");
        fixtures.registerMember(snowflake, guild2, "Alice", "alice");

        Guild g1 = mock(Guild.class);
        when(g1.getId()).thenReturn(String.valueOf(guild1));
        when(g1.getName()).thenReturn("Guild One");
        when(g1.getIconUrl()).thenReturn(null);
        when(g1.getMemberCount()).thenReturn(10);
        when(jda.getGuildById(guild1)).thenReturn(g1);

        Guild g2 = mock(Guild.class);
        when(g2.getId()).thenReturn(String.valueOf(guild2));
        when(g2.getName()).thenReturn("Guild Two");
        when(g2.getIconUrl()).thenReturn(null);
        when(g2.getMemberCount()).thenReturn(5);
        when(jda.getGuildById(guild2)).thenReturn(g2);

        mockMvc.perform(MockMvcRequestBuilders.get("/guild").with(authedAs(snowflake)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(String.valueOf(guild1), String.valueOf(guild2))));
    }

    @Test
    void userInNoGuilds_fallsBackToConfiguredGuild() throws Exception {
        // Fresh user with no registerMember calls — has no guild_member rows. The configured
        // guild-id is 0; mock returns null so we get an empty list.
        String snowflake = TestIds.nextSnowflake();
        when(jda.getGuildById(0L)).thenReturn(null);

        mockMvc.perform(MockMvcRequestBuilders.get("/guild").with(authedAs(snowflake)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
