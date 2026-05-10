package dev.tylercash.event.discord;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.SharedPostgres;
import dev.tylercash.event.test.TestIds;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class GuildSettingsControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    // Dedicated context so the @MockitoBean discordAuthService isn't polluted by sibling
    // HTTP tests that share the parent context.
    @DynamicPropertySource
    static void datasourceOverride(DynamicPropertyRegistry r) {
        SharedPostgres.registerIsolatedDatabase(r, GuildSettingsControllerHttpIntegrationTest.class);
    }

    private String USER_ID;
    private long GUILD_1;
    private long GUILD_2;

    @org.junit.jupiter.api.BeforeEach
    void allocateTestIds() {
        USER_ID = TestIds.nextSnowflake();
        GUILD_1 = TestIds.nextLong();
        GUILD_2 = TestIds.nextLong();
    }

    @Test
    void anonymous_get_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/guild/{guildId}/settings", GUILD_1))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonMember_get_returns403() throws Exception {
        // Register user as member of GUILD_2, not GUILD_1
        fixtures.registerMember(USER_ID, GUILD_2, "Bob", "bob");

        mockMvc.perform(MockMvcRequestBuilders.get("/guild/{guildId}/settings", GUILD_1)
                        .with(authedAs(USER_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    void member_get_returnsDefaults() throws Exception {
        fixtures.registerMember(USER_ID, GUILD_1, "Alice", "alice");

        mockMvc.perform(MockMvcRequestBuilders.get("/guild/{guildId}/settings", GUILD_1)
                        .with(authedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryLocationPlaceId").doesNotExist())
                .andExpect(jsonPath("$.primaryLocationName").doesNotExist())
                .andExpect(jsonPath("$.primaryLocationLat").doesNotExist())
                .andExpect(jsonPath("$.primaryLocationLng").doesNotExist());
    }

    @Test
    void member_get_returnsExistingSettings() throws Exception {
        fixtures.registerMember(USER_ID, GUILD_1, "Alice", "alice");
        jdbc.update(
                "INSERT INTO guild (guild_id, primary_location_place_id, primary_location_name, primary_location_lat, primary_location_lng) VALUES (?, ?, ?, ?, ?)",
                GUILD_1,
                "place-1",
                "Melbourne",
                -37.8,
                144.9);

        mockMvc.perform(MockMvcRequestBuilders.get("/guild/{guildId}/settings", GUILD_1)
                        .with(authedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryLocationPlaceId").value("place-1"))
                .andExpect(jsonPath("$.primaryLocationName").value("Melbourne"))
                .andExpect(jsonPath("$.primaryLocationLat").value(-37.8))
                .andExpect(jsonPath("$.primaryLocationLng").value(144.9));
    }

    @Test
    void nonOwner_patch_returns403() throws Exception {
        fixtures.registerMember(USER_ID, GUILD_1, "Alice", "alice");
        // discordAuthService.isGuildOwner returns false by default (Mockito)

        String body =
                "{\"primaryLocationPlaceId\":\"place-2\",\"primaryLocationName\":\"Sydney\",\"primaryLocationLat\":-33.86,\"primaryLocationLng\":151.20}";

        mockMvc.perform(MockMvcRequestBuilders.patch("/guild/{guildId}/settings", GUILD_1)
                        .with(authedAs(USER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void owner_patch_persists() throws Exception {
        fixtures.registerMember(USER_ID, GUILD_1, "Alice", "alice");
        when(discordAuthService.isGuildOwner(GUILD_1, Long.parseLong(USER_ID))).thenReturn(true);

        String body =
                "{\"primaryLocationPlaceId\":\"place-2\",\"primaryLocationName\":\"Sydney\",\"primaryLocationLat\":-33.86,\"primaryLocationLng\":151.20}";

        mockMvc.perform(MockMvcRequestBuilders.patch("/guild/{guildId}/settings", GUILD_1)
                        .with(authedAs(USER_ID))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryLocationPlaceId").value("place-2"))
                .andExpect(jsonPath("$.primaryLocationName").value("Sydney"));

        // Verify persistence via GET
        mockMvc.perform(MockMvcRequestBuilders.get("/guild/{guildId}/settings", GUILD_1)
                        .with(authedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryLocationPlaceId").value("place-2"))
                .andExpect(jsonPath("$.primaryLocationName").value("Sydney"))
                .andExpect(jsonPath("$.primaryLocationLat").value(-33.86))
                .andExpect(jsonPath("$.primaryLocationLng").value(151.20));
    }
}
