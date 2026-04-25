package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.discord.model.DiscordUserCache;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.user.OAuth2User;

@ExtendWith(MockitoExtension.class)
class AvatarControllerTest {

    @Test
    void getAvatar_returns200WithBytes_whenAvatarStored() {
        DiscordUserCacheRepository repo = mock(DiscordUserCacheRepository.class);
        byte[] bytes = new byte[] {1, 2, 3};
        DiscordUserCache cached = new DiscordUserCache("123", "User", Instant.now(), bytes, "image/webp", java.util.Set.of(456L));
        when(repo.findById("123")).thenReturn(Optional.of(cached));
        when(repo.haveSharedGuild("789", "123")).thenReturn(true);

        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("id")).thenReturn("789");

        AvatarController controller = new AvatarController(repo);
        ResponseEntity<byte[]> response = controller.getAvatar("123", principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(bytes);
        assertThat(response.getHeaders().getContentType()).hasToString("image/webp");
        assertThat(response.getHeaders().getCacheControl()).contains("max-age=86400");
    }

    @Test
    void getAvatar_returns404_whenUserNotCached() {
        DiscordUserCacheRepository repo = mock(DiscordUserCacheRepository.class);
        when(repo.findById("999")).thenReturn(Optional.empty());
        when(repo.haveSharedGuild("789", "999")).thenReturn(true);

        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("id")).thenReturn("789");

        AvatarController controller = new AvatarController(repo);
        ResponseEntity<byte[]> response = controller.getAvatar("999", principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getAvatar_returns404_whenAvatarBytesNull() {
        DiscordUserCacheRepository repo = mock(DiscordUserCacheRepository.class);
        DiscordUserCache cached = new DiscordUserCache("123", "User", Instant.now(), null, null, java.util.Collections.emptySet());
        when(repo.findById("123")).thenReturn(Optional.of(cached));
        when(repo.haveSharedGuild("789", "123")).thenReturn(true);

        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("id")).thenReturn("789");

        AvatarController controller = new AvatarController(repo);
        ResponseEntity<byte[]> response = controller.getAvatar("123", principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
