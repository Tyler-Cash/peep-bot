package dev.tylercash.event.discord;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class GuildControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    private static final String SNOWFLAKE = "200";
    private static final long GUILD_1 = 1001L;
    private static final long GUILD_2 = 1002L;

    @Test
    void anonymous_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/guild")).andExpect(status().isUnauthorized());
    }

    @Test
    void userInOneGuild_returnsThatGuild() throws Exception {
        fixtures.registerMember(SNOWFLAKE, GUILD_1, "Alice", "alice");

        Guild g = mock(Guild.class);
        when(g.getId()).thenReturn(String.valueOf(GUILD_1));
        when(g.getName()).thenReturn("Guild One");
        when(g.getIconUrl()).thenReturn(null);
        when(g.getMemberCount()).thenReturn(10);
        when(jda.getGuildById(GUILD_1)).thenReturn(g);

        mockMvc.perform(MockMvcRequestBuilders.get("/guild").with(authedAs(SNOWFLAKE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(String.valueOf(GUILD_1)))
                .andExpect(jsonPath("$[0].name").value("Guild One"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void userInMultipleGuilds_returnsAll() throws Exception {
        fixtures.registerMember(SNOWFLAKE, GUILD_1, "Alice", "alice");
        fixtures.registerMember(SNOWFLAKE, GUILD_2, "Alice", "alice");

        Guild g1 = mock(Guild.class);
        when(g1.getId()).thenReturn(String.valueOf(GUILD_1));
        when(g1.getName()).thenReturn("Guild One");
        when(g1.getIconUrl()).thenReturn(null);
        when(g1.getMemberCount()).thenReturn(10);
        when(jda.getGuildById(GUILD_1)).thenReturn(g1);

        Guild g2 = mock(Guild.class);
        when(g2.getId()).thenReturn(String.valueOf(GUILD_2));
        when(g2.getName()).thenReturn("Guild Two");
        when(g2.getIconUrl()).thenReturn(null);
        when(g2.getMemberCount()).thenReturn(5);
        when(jda.getGuildById(GUILD_2)).thenReturn(g2);

        mockMvc.perform(MockMvcRequestBuilders.get("/guild").with(authedAs(SNOWFLAKE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void userInNoGuilds_fallsBackToConfiguredGuild() throws Exception {
        // No registerMember calls — user has no guild memberships.
        // configured guild-id is 0; mock returns null so we get empty list.
        when(jda.getGuildById(0L)).thenReturn(null);

        mockMvc.perform(MockMvcRequestBuilders.get("/guild").with(authedAs(SNOWFLAKE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
