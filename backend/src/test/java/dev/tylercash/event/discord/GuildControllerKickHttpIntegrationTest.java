package dev.tylercash.event.discord;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.SharedPostgres;
import dev.tylercash.event.test.TestIds;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class GuildControllerKickHttpIntegrationTest extends AbstractHttpIntegrationTest {

    @DynamicPropertySource
    static void datasourceOverride(DynamicPropertyRegistry r) {
        SharedPostgres.registerIsolatedDatabase(r, GuildControllerKickHttpIntegrationTest.class);
    }

    @Test
    void nonOwner_cannotKickBot() throws Exception {
        String snowflake = TestIds.nextSnowflake();
        long guildId = TestIds.nextLong();
        fixtures.registerMember(snowflake, guildId, "Alice", "alice");
        Guild g = mock(Guild.class);
        when(g.getName()).thenReturn("Porch Pigeons");
        when(jda.getGuildById(guildId)).thenReturn(g);

        mockMvc.perform(MockMvcRequestBuilders.delete("/guild/{id}", guildId)
                        .with(authedAs(snowflake))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmGuildName\":\"Porch Pigeons\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void mismatchedGuildName_returns400() throws Exception {
        String snowflake = TestIds.nextSnowflake();
        long guildId = TestIds.nextLong();
        fixtures.registerMember(snowflake, guildId, "Alice", "alice");
        when(discordAuthService.isGuildOwner(guildId, Long.parseLong(snowflake)))
                .thenReturn(true);
        Guild g = mock(Guild.class);
        when(g.getName()).thenReturn("Porch Pigeons");
        when(jda.getGuildById(guildId)).thenReturn(g);

        mockMvc.perform(MockMvcRequestBuilders.delete("/guild/{id}", guildId)
                        .with(authedAs(snowflake))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmGuildName\":\"wrong name\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ownerWithMatchingName_triggersLeave() throws Exception {
        String snowflake = TestIds.nextSnowflake();
        long guildId = TestIds.nextLong();
        fixtures.registerMember(snowflake, guildId, "Alice", "alice");
        when(discordAuthService.isGuildOwner(guildId, Long.parseLong(snowflake)))
                .thenReturn(true);
        Guild g = mock(Guild.class);
        when(g.getName()).thenReturn("Porch Pigeons");
        RestAction<Void> leaveAction = mock(RestAction.class);
        when(g.leave()).thenReturn(leaveAction);
        when(jda.getGuildById(guildId)).thenReturn(g);

        mockMvc.perform(MockMvcRequestBuilders.delete("/guild/{id}", guildId)
                        .with(authedAs(snowflake))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmGuildName\":\"  porch pigeons  \"}"))
                .andExpect(status().isNoContent());

        verify(leaveAction).queue(any(), any());
    }

    @Test
    void emptyConfirmName_returns400() throws Exception {
        String snowflake = TestIds.nextSnowflake();
        long guildId = TestIds.nextLong();
        fixtures.registerMember(snowflake, guildId, "Alice", "alice");
        when(discordAuthService.isGuildOwner(guildId, Long.parseLong(snowflake)))
                .thenReturn(true);

        mockMvc.perform(MockMvcRequestBuilders.delete("/guild/{id}", guildId)
                        .with(authedAs(snowflake))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmGuildName\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
