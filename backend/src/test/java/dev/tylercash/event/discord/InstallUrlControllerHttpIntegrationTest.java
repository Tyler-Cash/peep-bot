package dev.tylercash.event.discord;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import org.junit.jupiter.api.Test;

class InstallUrlControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    @Test
    void anonymous_returnsInstallUrl() throws Exception {
        mockMvc.perform(get("/install-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url")
                        .value(org.hamcrest.Matchers.containsString(
                                "https://discord.com/api/oauth2/authorize?client_id=test&permissions=")));
    }

    @Test
    void urlContainsRequiredPermissions() throws Exception {
        mockMvc.perform(get("/install-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.containsString("permissions=268954640")))
                .andExpect(jsonPath("$.url")
                        .value(org.hamcrest.Matchers.containsString("scope=bot+applications.commands")));
    }
}
