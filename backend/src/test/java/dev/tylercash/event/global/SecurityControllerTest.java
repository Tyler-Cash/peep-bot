package dev.tylercash.event.global;

import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.security.UserInfoDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecurityControllerTest {

    private static final long GUILD_ID = 123456789L;
    private static final String DISCORD_ID = "987654321";
    private static final String USERNAME = "testuser";

    @Test
    void isLoggedIn_returnsUserInfoDtoWithAdminTrue_whenUserHasAdminRole() {
        DiscordService discordService = mock(DiscordService.class);
        DiscordConfiguration config = mock(DiscordConfiguration.class);
        OAuth2User principal = mock(OAuth2User.class);

        when(config.getGuildId()).thenReturn(GUILD_ID);
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(principal.getAttribute("username")).thenReturn(USERNAME);
        when(discordService.isUserAdminOfServer(GUILD_ID, Long.parseLong(DISCORD_ID))).thenReturn(true);

        SecurityController controller = new SecurityController(discordService, config);
        UserInfoDto result = controller.isLoggedIn(principal);

        assertThat(result.getUsername()).isEqualTo(USERNAME);
        assertThat(result.getDiscordId()).isEqualTo(DISCORD_ID);
        assertThat(result.isAdmin()).isTrue();
    }

    @Test
    void isLoggedIn_returnsUserInfoDtoWithAdminFalse_whenUserLacksAdminRole() {
        DiscordService discordService = mock(DiscordService.class);
        DiscordConfiguration config = mock(DiscordConfiguration.class);
        OAuth2User principal = mock(OAuth2User.class);

        when(config.getGuildId()).thenReturn(GUILD_ID);
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(principal.getAttribute("username")).thenReturn(USERNAME);
        when(discordService.isUserAdminOfServer(GUILD_ID, Long.parseLong(DISCORD_ID))).thenReturn(false);

        SecurityController controller = new SecurityController(discordService, config);
        UserInfoDto result = controller.isLoggedIn(principal);

        assertThat(result.isAdmin()).isFalse();
        assertThat(result.getUsername()).isEqualTo(USERNAME);
        assertThat(result.getDiscordId()).isEqualTo(DISCORD_ID);
    }

    @Test
    void isLoggedIn_throwsUnauthorized_whenPrincipalIsNull() {
        DiscordService discordService = mock(DiscordService.class);
        DiscordConfiguration config = mock(DiscordConfiguration.class);

        SecurityController controller = new SecurityController(discordService, config);

        assertThatThrownBy(() -> controller.isLoggedIn(null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });

        verifyNoInteractions(discordService);
    }

    @Test
    void isLoggedIn_usesGuildIdFromConfiguration() {
        DiscordService discordService = mock(DiscordService.class);
        DiscordConfiguration config = mock(DiscordConfiguration.class);
        OAuth2User principal = mock(OAuth2User.class);

        long specificGuildId = 999888777L;
        when(config.getGuildId()).thenReturn(specificGuildId);
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(principal.getAttribute("username")).thenReturn(USERNAME);
        when(discordService.isUserAdminOfServer(eq(specificGuildId), anyLong())).thenReturn(false);

        SecurityController controller = new SecurityController(discordService, config);
        controller.isLoggedIn(principal);

        verify(discordService).isUserAdminOfServer(specificGuildId, Long.parseLong(DISCORD_ID));
    }
}
