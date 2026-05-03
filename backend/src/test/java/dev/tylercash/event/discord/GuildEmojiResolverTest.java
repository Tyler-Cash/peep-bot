package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import org.junit.jupiter.api.Test;

class GuildEmojiResolverTest {

    @Test
    void resolveUsesUnicodeWhenNoCustomEmojiMatches() {
        net.dv8tion.jda.api.entities.Guild jdaGuild = mock(net.dv8tion.jda.api.entities.Guild.class);
        when(jdaGuild.getIdLong()).thenReturn(1L);
        when(jdaGuild.getEmojisByName(eq("accepted"), eq(true))).thenReturn(List.of());
        when(jdaGuild.getEmojisByName(eq("declined"), eq(true))).thenReturn(List.of());
        when(jdaGuild.getEmojisByName(eq("maybe"), eq(true))).thenReturn(List.of());

        Guild row = Guild.withDefaults(1L);
        GuildEmojiResolver resolver = new GuildEmojiResolver();
        resolver.resolve(jdaGuild, row);

        GuildEmojiResolver.ResolvedEmoji e = resolver.forGuild(1L);
        assertThat(e.accepted()).isEqualTo("✅");
        assertThat(e.declined()).isEqualTo("❌");
        assertThat(e.maybe()).isEqualTo("❓");
    }

    @Test
    void resolveUsesCustomEmojiMentionWhenAvailable() {
        net.dv8tion.jda.api.entities.Guild jdaGuild = mock(net.dv8tion.jda.api.entities.Guild.class);
        when(jdaGuild.getIdLong()).thenReturn(2L);
        RichCustomEmoji custom = mock(RichCustomEmoji.class);
        when(custom.getAsMention()).thenReturn("<:accepted:99>");
        when(jdaGuild.getEmojisByName(eq("accepted"), eq(true))).thenReturn(List.of(custom));
        when(jdaGuild.getEmojisByName(eq("declined"), eq(true))).thenReturn(List.of());
        when(jdaGuild.getEmojisByName(eq("maybe"), eq(true))).thenReturn(List.of());

        Guild row = Guild.withDefaults(2L);
        GuildEmojiResolver resolver = new GuildEmojiResolver();
        resolver.resolve(jdaGuild, row);

        assertThat(resolver.forGuild(2L).accepted()).isEqualTo("<:accepted:99>");
    }

    @Test
    void forGuildFallsBackToUnicodeWhenNotResolved() {
        GuildEmojiResolver resolver = new GuildEmojiResolver();
        GuildEmojiResolver.ResolvedEmoji e = resolver.forGuild(404L);
        assertThat(e.accepted()).isEqualTo("✅");
        assertThat(e.declined()).isEqualTo("❌");
        assertThat(e.maybe()).isEqualTo("❓");
    }
}
