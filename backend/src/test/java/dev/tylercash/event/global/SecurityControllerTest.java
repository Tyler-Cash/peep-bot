package dev.tylercash.event.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.security.UserInfoDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class SecurityControllerTest {

    private static final long GUILD_ID = 123456789L;
    private static final String DISCORD_ID = "987654321";
    private static final String USERNAME = "testuser";

    @Test
    void isLoggedIn_returnsAdminGuildIdsWhenUserIsAdmin() {
        DiscordService discordService = mock(DiscordService.class);
        GuildRepository guildRepository = mock(GuildRepository.class);
        OAuth2User principal = mock(OAuth2User.class);

        Guild guild = Guild.withDefaults(GUILD_ID);
        when(guildRepository.findAllByActiveTrue()).thenReturn(List.of(guild));
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(principal.getAttribute("username")).thenReturn(USERNAME);
        when(discordService.isUserAdminOfServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(true);

        SecurityController controller = new SecurityController(discordService, guildRepository);
        UserInfoDto result = controller.isLoggedIn(principal);

        assertThat(result.username()).isEqualTo(USERNAME);
        assertThat(result.discordId()).isEqualTo(DISCORD_ID);
        assertThat(result.adminGuildIds()).containsExactly(String.valueOf(GUILD_ID));
        assertThat(result.avatarUrl()).isEqualTo("/api/avatar/" + DISCORD_ID);
    }

    @Test
    void isLoggedIn_returnsEmptyAdminGuildIdsWhenUserLacksAdmin() {
        DiscordService discordService = mock(DiscordService.class);
        GuildRepository guildRepository = mock(GuildRepository.class);
        OAuth2User principal = mock(OAuth2User.class);

        Guild guild = Guild.withDefaults(GUILD_ID);
        when(guildRepository.findAllByActiveTrue()).thenReturn(List.of(guild));
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(principal.getAttribute("username")).thenReturn(USERNAME);
        when(discordService.isUserAdminOfServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(false);

        SecurityController controller = new SecurityController(discordService, guildRepository);
        UserInfoDto result = controller.isLoggedIn(principal);

        assertThat(result.adminGuildIds()).isEmpty();
        assertThat(result.username()).isEqualTo(USERNAME);
    }

    @Test
    void isLoggedIn_throwsUnauthorizedWhenPrincipalIsNull() {
        DiscordService discordService = mock(DiscordService.class);
        GuildRepository guildRepository = mock(GuildRepository.class);

        SecurityController controller = new SecurityController(discordService, guildRepository);

        assertThatThrownBy(() -> controller.isLoggedIn(null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });

        verifyNoInteractions(discordService, guildRepository);
    }

    @Test
    void isLoggedIn_iteratesAllActiveGuilds() {
        DiscordService discordService = mock(DiscordService.class);
        GuildRepository guildRepository = mock(GuildRepository.class);
        OAuth2User principal = mock(OAuth2User.class);

        Guild g1 = Guild.withDefaults(111L);
        Guild g2 = Guild.withDefaults(222L);
        when(guildRepository.findAllByActiveTrue()).thenReturn(List.of(g1, g2));
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(principal.getAttribute("username")).thenReturn(USERNAME);
        when(discordService.isUserAdminOfServer(111L, Long.parseLong(DISCORD_ID)))
                .thenReturn(true);
        when(discordService.isUserAdminOfServer(222L, Long.parseLong(DISCORD_ID)))
                .thenReturn(false);

        SecurityController controller = new SecurityController(discordService, guildRepository);
        UserInfoDto result = controller.isLoggedIn(principal);

        assertThat(result.adminGuildIds()).containsExactly("111");
        verify(guildRepository).findAllByActiveTrue();
        verify(discordService).isUserAdminOfServer(111L, Long.parseLong(DISCORD_ID));
        verify(discordService).isUserAdminOfServer(222L, Long.parseLong(DISCORD_ID));
    }
}
