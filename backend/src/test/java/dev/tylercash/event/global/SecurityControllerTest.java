package dev.tylercash.event.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import dev.tylercash.event.discord.DiscordAuthService;
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
    void isLoggedIn_returnsOrganiserGuildIdsWhenUserIsOrganiser() {
        DiscordService discordService = mock(DiscordService.class);
        DiscordAuthService discordAuthService = mock(DiscordAuthService.class);
        GuildRepository guildRepository = mock(GuildRepository.class);
        OAuth2User principal = mock(OAuth2User.class);

        Guild guild = Guild.withDefaults(GUILD_ID);
        when(guildRepository.findAllByActiveTrue()).thenReturn(List.of(guild));
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(principal.getAttribute("username")).thenReturn(USERNAME);
        when(discordService.isUserOrganiserOfServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(true);
        when(discordAuthService.isGuildOwner(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(false);

        SecurityController controller = new SecurityController(discordService, discordAuthService, guildRepository);
        UserInfoDto result = controller.isLoggedIn(principal);

        assertThat(result.username()).isEqualTo(USERNAME);
        assertThat(result.discordId()).isEqualTo(DISCORD_ID);
        assertThat(result.organiserGuildIds()).containsExactly(String.valueOf(GUILD_ID));
        assertThat(result.ownedGuildIds()).isEmpty();
        assertThat(result.avatarUrl()).isEqualTo("/api/avatar/" + DISCORD_ID);
    }

    @Test
    void isLoggedIn_returnsOwnedGuildIdsWhenUserIsOwner() {
        DiscordService discordService = mock(DiscordService.class);
        DiscordAuthService discordAuthService = mock(DiscordAuthService.class);
        GuildRepository guildRepository = mock(GuildRepository.class);
        OAuth2User principal = mock(OAuth2User.class);

        Guild guild = Guild.withDefaults(GUILD_ID);
        when(guildRepository.findAllByActiveTrue()).thenReturn(List.of(guild));
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(principal.getAttribute("username")).thenReturn(USERNAME);
        when(discordService.isUserOrganiserOfServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(false);
        when(discordAuthService.isGuildOwner(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(true);

        SecurityController controller = new SecurityController(discordService, discordAuthService, guildRepository);
        UserInfoDto result = controller.isLoggedIn(principal);

        assertThat(result.organiserGuildIds()).isEmpty();
        assertThat(result.ownedGuildIds()).containsExactly(String.valueOf(GUILD_ID));
    }

    @Test
    void isLoggedIn_returnsEmptyListsWhenUserLacksOrganiserAndOwner() {
        DiscordService discordService = mock(DiscordService.class);
        DiscordAuthService discordAuthService = mock(DiscordAuthService.class);
        GuildRepository guildRepository = mock(GuildRepository.class);
        OAuth2User principal = mock(OAuth2User.class);

        Guild guild = Guild.withDefaults(GUILD_ID);
        when(guildRepository.findAllByActiveTrue()).thenReturn(List.of(guild));
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(principal.getAttribute("username")).thenReturn(USERNAME);
        when(discordService.isUserOrganiserOfServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(false);
        when(discordAuthService.isGuildOwner(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(false);

        SecurityController controller = new SecurityController(discordService, discordAuthService, guildRepository);
        UserInfoDto result = controller.isLoggedIn(principal);

        assertThat(result.organiserGuildIds()).isEmpty();
        assertThat(result.ownedGuildIds()).isEmpty();
        assertThat(result.username()).isEqualTo(USERNAME);
    }

    @Test
    void isLoggedIn_throwsUnauthorizedWhenPrincipalIsNull() {
        DiscordService discordService = mock(DiscordService.class);
        DiscordAuthService discordAuthService = mock(DiscordAuthService.class);
        GuildRepository guildRepository = mock(GuildRepository.class);

        SecurityController controller = new SecurityController(discordService, discordAuthService, guildRepository);

        assertThatThrownBy(() -> controller.isLoggedIn(null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });

        verifyNoInteractions(discordService, discordAuthService, guildRepository);
    }

    @Test
    void isLoggedIn_iteratesAllActiveGuilds() {
        DiscordService discordService = mock(DiscordService.class);
        DiscordAuthService discordAuthService = mock(DiscordAuthService.class);
        GuildRepository guildRepository = mock(GuildRepository.class);
        OAuth2User principal = mock(OAuth2User.class);

        Guild g1 = Guild.withDefaults(111L);
        Guild g2 = Guild.withDefaults(222L);
        when(guildRepository.findAllByActiveTrue()).thenReturn(List.of(g1, g2));
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(principal.getAttribute("username")).thenReturn(USERNAME);
        when(discordService.isUserOrganiserOfServer(111L, Long.parseLong(DISCORD_ID)))
                .thenReturn(true);
        when(discordService.isUserOrganiserOfServer(222L, Long.parseLong(DISCORD_ID)))
                .thenReturn(false);
        when(discordAuthService.isGuildOwner(111L, Long.parseLong(DISCORD_ID))).thenReturn(false);
        when(discordAuthService.isGuildOwner(222L, Long.parseLong(DISCORD_ID))).thenReturn(false);

        SecurityController controller = new SecurityController(discordService, discordAuthService, guildRepository);
        UserInfoDto result = controller.isLoggedIn(principal);

        assertThat(result.organiserGuildIds()).containsExactly("111");
        assertThat(result.ownedGuildIds()).isEmpty();
        verify(guildRepository).findAllByActiveTrue();
        verify(discordService).isUserOrganiserOfServer(111L, Long.parseLong(DISCORD_ID));
        verify(discordService).isUserOrganiserOfServer(222L, Long.parseLong(DISCORD_ID));
    }
}
