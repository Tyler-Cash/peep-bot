package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.discord.model.DiscordUserCache;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DiscordUserCacheTest {

    @Test
    void avatarFieldsDefaultToNull() {
        DiscordUserCache cache = new DiscordUserCache("123", "TestUser", Instant.now(), null, null, java.util.Collections.emptySet());
        assertThat(cache.getAvatarBytes()).isNull();
        assertThat(cache.getAvatarContentType()).isNull();
    }

    @Test
    void avatarFieldsCanBeSet() {
        byte[] bytes = new byte[] {1, 2, 3};
        DiscordUserCache cache = new DiscordUserCache("123", "TestUser", Instant.now(), bytes, "image/webp", java.util.Collections.emptySet());
        assertThat(cache.getAvatarBytes()).isEqualTo(bytes);
        assertThat(cache.getAvatarContentType()).isEqualTo("image/webp");
    }
}
