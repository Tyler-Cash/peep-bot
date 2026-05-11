package dev.tylercash.event.discord;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.SharedPostgres;
import dev.tylercash.event.test.TestIds;
import java.util.List;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class GuildDirectoryControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    @DynamicPropertySource
    static void datasourceOverride(DynamicPropertyRegistry r) {
        SharedPostgres.registerIsolatedDatabase(r, GuildDirectoryControllerHttpIntegrationTest.class);
    }

    @Test
    void rolesEndpoint_deniesNonMember() throws Exception {
        long guildId = TestIds.nextLong();
        String snowflake = TestIds.nextSnowflake();

        mockMvc.perform(MockMvcRequestBuilders.get("/guild/{id}/roles", guildId).with(authedAs(snowflake)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rolesEndpoint_returnsSortedNonManagedNonEveryoneRoles() throws Exception {
        long guildId = TestIds.nextLong();
        String snowflake = TestIds.nextSnowflake();
        fixtures.registerMember(snowflake, guildId, "Alice", "alice");

        Guild g = mock(Guild.class);
        when(jda.getGuildById(guildId)).thenReturn(g);
        List<Role> roles = List.of(
                stubRole("0", "@everyone", true),
                stubRole("100", "Zebra", false),
                stubRole("200", "Aardvark", false),
                stubRole("300", "MangoBot", true),
                stubRole("400", "Mango", false));
        when(g.getRoles()).thenReturn(roles);

        mockMvc.perform(MockMvcRequestBuilders.get("/guild/{id}/roles", guildId).with(authedAs(snowflake)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("Aardvark"))
                .andExpect(jsonPath("$[1].name").value("Mango"))
                .andExpect(jsonPath("$[2].name").value("Zebra"));
    }

    @Test
    void categoriesEndpoint_returnsSortedCategories() throws Exception {
        long guildId = TestIds.nextLong();
        String snowflake = TestIds.nextSnowflake();
        fixtures.registerMember(snowflake, guildId, "Alice", "alice");

        Guild g = mock(Guild.class);
        when(jda.getGuildById(guildId)).thenReturn(g);
        List<Category> categories = List.of(stubCategory("200", "Zeta"), stubCategory("100", "Alpha"));
        when(g.getCategories()).thenReturn(categories);

        mockMvc.perform(MockMvcRequestBuilders.get("/guild/{id}/categories", guildId)
                        .with(authedAs(snowflake)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Alpha"))
                .andExpect(jsonPath("$[0].id").value("100"))
                .andExpect(jsonPath("$[1].name").value("Zeta"));
    }

    @Test
    void rolesEndpoint_returnsEmptyWhenJdaCacheCold() throws Exception {
        long guildId = TestIds.nextLong();
        String snowflake = TestIds.nextSnowflake();
        fixtures.registerMember(snowflake, guildId, "Alice", "alice");
        when(jda.getGuildById(guildId)).thenReturn(null);

        mockMvc.perform(MockMvcRequestBuilders.get("/guild/{id}/roles", guildId).with(authedAs(snowflake)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private static Role stubRole(String id, String name, boolean managed) {
        Role r = mock(Role.class);
        when(r.getId()).thenReturn(id);
        when(r.getName()).thenReturn(name);
        when(r.isManaged()).thenReturn(managed);
        return r;
    }

    private static Category stubCategory(String id, String name) {
        Category c = mock(Category.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        return c;
    }
}
