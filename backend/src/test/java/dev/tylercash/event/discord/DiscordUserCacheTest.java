package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tylercash.event.discord.model.DiscordGuildMember;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DiscordUserCacheTest {

    @Test
    void avatarFieldsDefaultToNull() {
        DiscordGuildMember member = new DiscordGuildMember(1L, "123", "TestUser", null, null, Instant.now());
        assertThat(member.getAvatarBytes()).isNull();
        assertThat(member.getAvatarContentType()).isNull();
    }

    @Test
    void avatarFieldsCanBeSet() {
        byte[] bytes = new byte[] {1, 2, 3};
        DiscordGuildMember member = new DiscordGuildMember(1L, "123", "TestUser", bytes, "image/webp", Instant.now());
        assertThat(member.getAvatarBytes()).isEqualTo(bytes);
        assertThat(member.getAvatarContentType()).isEqualTo("image/webp");
    }
}
