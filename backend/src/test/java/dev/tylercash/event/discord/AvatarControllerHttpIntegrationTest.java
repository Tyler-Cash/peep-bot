package dev.tylercash.event.discord;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.tylercash.event.test.AbstractHttpIntegrationTest;
import dev.tylercash.event.test.SharedPostgres;
import dev.tylercash.event.test.TestIds;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.ImageProxy;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class AvatarControllerHttpIntegrationTest extends AbstractHttpIntegrationTest {

    // Dedicated context (different datasource URL) so the @MockitoBean discordService
    // can't be polluted by sibling HTTP test classes sharing the parent context.
    @DynamicPropertySource
    static void datasourceOverride(DynamicPropertyRegistry r) {
        SharedPostgres.registerIsolatedDatabase(r, AvatarControllerHttpIntegrationTest.class);
    }

    private String VIEWER;
    private String TARGET;
    private long GUILD_1;
    private long GUILD_2;

    @org.junit.jupiter.api.BeforeEach
    void allocateTestIds() {
        VIEWER = TestIds.nextSnowflake();
        TARGET = TestIds.nextSnowflake();
        GUILD_1 = TestIds.nextLong();
        GUILD_2 = TestIds.nextLong();
    }

    @Test
    void anonymous_returns401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/avatar/{snowflake}", TARGET))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void notSharedGuild_returns403() throws Exception {
        fixtures.registerMember(VIEWER, GUILD_1, "Viewer", "viewer");
        fixtures.registerMember(TARGET, GUILD_2, "Target", "target");

        mockMvc.perform(MockMvcRequestBuilders.get("/avatar/{snowflake}", TARGET)
                        .with(authedAs(VIEWER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void cacheHit_returnsBytes() throws Exception {
        fixtures.registerMember(VIEWER, GUILD_1, "Viewer", "viewer");
        fixtures.registerMember(TARGET, GUILD_1, "Target", "target");

        jdbc.update(
                "UPDATE guild_member SET avatar_bytes = ?, avatar_content_type = ? WHERE snowflake = ? AND guild_id = ?",
                new byte[] {1, 2, 3},
                "image/webp",
                TARGET,
                GUILD_1);

        mockMvc.perform(MockMvcRequestBuilders.get("/avatar/{snowflake}", TARGET)
                        .with(authedAs(VIEWER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/webp"))
                .andExpect(content().bytes(new byte[] {1, 2, 3}));
    }

    @Test
    void cacheMiss_redirectsToJdaUrl() throws Exception {
        fixtures.registerMember(VIEWER, GUILD_1, "Viewer", "viewer");
        fixtures.registerMember(TARGET, GUILD_1, "Target", "target");
        // avatar_bytes is null by default after registerMember

        String avatarUrl = "https://cdn.discordapp.com/avatars/402/abc.webp";
        ImageProxy proxy = mock(ImageProxy.class);
        when(proxy.getUrl(256)).thenReturn(avatarUrl);

        Member member = mock(Member.class);
        User user = mock(User.class);
        when(member.getUser()).thenReturn(user);
        when(user.getName()).thenReturn("target");
        when(member.getEffectiveAvatar()).thenReturn(proxy);
        when(member.getNickname()).thenReturn(null);
        when(member.getEffectiveName()).thenReturn("Target");

        when(discordService.getMemberFromServer(anyLong(), eq(Long.parseLong(TARGET))))
                .thenReturn(member);

        mockMvc.perform(MockMvcRequestBuilders.get("/avatar/{snowflake}", TARGET)
                        .with(authedAs(VIEWER)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", avatarUrl));
    }

    @Test
    void notFound_returns404() throws Exception {
        fixtures.registerMember(VIEWER, GUILD_1, "Viewer", "viewer");
        fixtures.registerMember(TARGET, GUILD_1, "Target", "target");
        // avatar_bytes is null, and discordService returns null

        when(discordService.getMemberFromServer(anyLong(), eq(Long.parseLong(TARGET))))
                .thenReturn(null);

        mockMvc.perform(MockMvcRequestBuilders.get("/avatar/{snowflake}", TARGET)
                        .with(authedAs(VIEWER)))
                .andExpect(status().isNotFound());
    }
}
