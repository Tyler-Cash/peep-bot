package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.discord.model.DiscordUserCache;
import java.time.Instant;
import java.util.Optional;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.ImageProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.user.OAuth2User;

@ExtendWith(MockitoExtension.class)
class AvatarControllerTest {

    @Mock
    private DiscordUserCacheRepository repo;

    @Mock
    private ObjectProvider<DiscordService> discordServiceProvider;

    @Mock
    private DiscordConfiguration discordConfiguration;

    @Mock
    private DiscordUserCacheService cacheService;

    @Mock
    private DiscordService discordService;

    private AvatarController controller;

    @BeforeEach
    void setUp() {
        controller = new AvatarController(repo, discordServiceProvider, discordConfiguration, cacheService);
    }

    @Test
    void getAvatar_returns200WithBytes_whenAvatarStored() {
        byte[] bytes = new byte[] {1, 2, 3};
        DiscordUserCache cached = new DiscordUserCache(
                "123", "User", "username", Instant.now(), bytes, "image/webp", java.util.Set.of(456L));
        when(repo.findById("123")).thenReturn(Optional.of(cached));
        when(repo.haveSharedGuild("789", "123")).thenReturn(true);

        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("id")).thenReturn("789");

        ResponseEntity<byte[]> response = controller.getAvatar("123", principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(bytes);
        assertThat(response.getHeaders().getContentType()).hasToString("image/webp");
        assertThat(response.getHeaders().getCacheControl()).contains("max-age=86400");
    }

    @Test
    void getAvatar_returns302_whenAvatarMissingInCacheButFoundInJDA() {
        when(repo.findById("123")).thenReturn(Optional.empty());
        when(repo.haveSharedGuild("789", "123")).thenReturn(true);
        when(discordServiceProvider.getIfAvailable()).thenReturn(discordService);
        when(discordConfiguration.getGuildId()).thenReturn(456L);

        Member member = mock(Member.class);
        net.dv8tion.jda.api.entities.User user = mock(net.dv8tion.jda.api.entities.User.class);
        when(member.getUser()).thenReturn(user);
        when(user.getName()).thenReturn("username");
        ImageProxy avatarProxy = mock(ImageProxy.class);
        when(member.getEffectiveAvatar()).thenReturn(avatarProxy);
        when(avatarProxy.getUrl(256)).thenReturn("http://discord.cdn/avatar.png");
        when(member.getNickname()).thenReturn("Nickname");
        when(discordService.getMemberFromServer(456L, 123L)).thenReturn(member);

        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("id")).thenReturn("789");

        ResponseEntity<byte[]> response = controller.getAvatar("123", principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("http://discord.cdn/avatar.png");
        verify(cacheService)
                .upsertUser(eq("123"), anyString(), eq("username"), eq("http://discord.cdn/avatar.png"), eq(456L));
    }

    @Test
    void getAvatar_returns404_whenUserNotCachedAndNotFoundInJDA() {
        when(repo.findById("999")).thenReturn(Optional.empty());
        when(repo.haveSharedGuild("789", "999")).thenReturn(true);
        when(discordServiceProvider.getIfAvailable()).thenReturn(discordService);
        when(discordConfiguration.getGuildId()).thenReturn(456L);
        when(discordService.getMemberFromServer(456L, 999L)).thenReturn(null);

        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("id")).thenReturn("789");

        ResponseEntity<byte[]> response = controller.getAvatar("999", principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
