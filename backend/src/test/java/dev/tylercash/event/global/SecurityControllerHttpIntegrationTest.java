package dev.tylercash.event.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class SecurityControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    private static final String SNOWFLAKE = "111";
    private static final long GUILD_ID = 700L;

    @Test
    void anonymousReturns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/auth/is-logged-in")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedReturnsUserInfo() throws Exception {
        fixtures.registerMember(SNOWFLAKE, GUILD_ID, "Alice", "alice");

        MvcResult result = mockMvc.perform(
                        MockMvcRequestBuilders.get("/auth/is-logged-in").with(authedAs(SNOWFLAKE)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"discordId\":\"" + SNOWFLAKE + "\"");
        assertThat(body).contains("\"avatarUrl\":\"/api/avatar/" + SNOWFLAKE + "\"");
        assertThat(body).contains("\"organiserGuildIds\":");
        assertThat(body).contains("\"ownedGuildIds\":");
    }
}
