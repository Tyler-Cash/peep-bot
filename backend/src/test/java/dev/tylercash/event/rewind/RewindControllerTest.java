package dev.tylercash.event.rewind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tylercash.event.discord.GuildMembershipService;
import dev.tylercash.event.rewind.model.RewindStatsDto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.user.OAuth2User;

class RewindControllerTest {
    private static final long GUILD_ID = 123456789L;
    private static final String SNOWFLAKE = "snowflake-123";

    private final RewindService rewindService = mock(RewindService.class);
    private final GuildMembershipService guildMembershipService = mock(GuildMembershipService.class);
    private final RewindController controller = new RewindController(rewindService, guildMembershipService);

    private OAuth2User principalWithSnowflake(String snowflake) {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("id")).thenReturn(snowflake);
        return principal;
    }

    @Test
    @DisplayName("getGuildStats delegates to the service with guildId and year")
    void getGuildStats_passesGuildIdAndYear() {
        RewindStatsDto expected = mock(RewindStatsDto.class);
        when(rewindService.getGuildStats(GUILD_ID, 2025)).thenReturn(expected);

        assertThat(controller.getGuildStats(principalWithSnowflake(SNOWFLAKE), GUILD_ID, 2025))
                .isSameAs(expected);
    }

    @Test
    @DisplayName("getGuildStats forwards a null year when no year is provided")
    void getGuildStats_forwardsNullYear() {
        controller.getGuildStats(principalWithSnowflake(SNOWFLAKE), GUILD_ID, null);

        verify(rewindService).getGuildStats(GUILD_ID, null);
    }

    @Test
    @DisplayName("getGuildStats checks guild membership before returning data")
    void getGuildStats_assertsMembership() {
        controller.getGuildStats(principalWithSnowflake(SNOWFLAKE), GUILD_ID, null);

        verify(guildMembershipService).assertMember(SNOWFLAKE, GUILD_ID);
    }

    @Test
    @DisplayName("getMyStats extracts the 'id' attribute from the OAuth2User and forwards it with guildId")
    void getMyStats_extractsSnowflakeFromOauthPrincipal() {
        RewindStatsDto expected = mock(RewindStatsDto.class);
        when(rewindService.getUserStats(SNOWFLAKE, GUILD_ID, 2024)).thenReturn(expected);

        assertThat(controller.getMyStats(principalWithSnowflake(SNOWFLAKE), GUILD_ID, 2024))
                .isSameAs(expected);
    }

    @Test
    @DisplayName("getMyStats checks guild membership before returning data")
    void getMyStats_assertsMembership() {
        controller.getMyStats(principalWithSnowflake(SNOWFLAKE), GUILD_ID, null);

        verify(guildMembershipService).assertMember(SNOWFLAKE, GUILD_ID);
    }

    @Test
    @DisplayName("getYears delegates to the service with guildId")
    void getYears_delegatesWithGuildId() {
        when(rewindService.getYears(GUILD_ID)).thenReturn(List.of(2026, 2025, 2024));

        assertThat(controller.getYears(principalWithSnowflake(SNOWFLAKE), GUILD_ID))
                .containsExactly(2026, 2025, 2024);
    }

    @Test
    @DisplayName("getYears checks guild membership before returning data")
    void getYears_assertsMembership() {
        controller.getYears(principalWithSnowflake(SNOWFLAKE), GUILD_ID);

        verify(guildMembershipService).assertMember(SNOWFLAKE, GUILD_ID);
    }
}
