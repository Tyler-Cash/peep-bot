package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.db.repository.GuildMemberRepository;
import dev.tylercash.event.discord.model.DiscordUserCache;
import dev.tylercash.event.discord.model.GuildMember;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DiscordUserCacheServiceTest {

    private static final long GUILD_ID = 999L;

    @Mock
    DiscordUserCacheRepository cacheRepository;

    @Mock
    GuildMemberRepository memberRepository;

    @Mock
    AvatarDownloadService avatarDownloadService;

    DiscordUserCacheService buildService() {
        return new DiscordUserCacheService(cacheRepository, memberRepository, avatarDownloadService);
    }

    // ── upsertUser ──────────────────────────────────────────────────────────

    @Test
    void upsertUser_downloadsAndStoresAvatar_whenUrlProvided() {
        byte[] fakeBytes = new byte[] {1, 2, 3};
        when(avatarDownloadService.download("https://cdn.discordapp.com/avatars/123/hash.webp"))
                .thenReturn(Optional.of(new AvatarDownloadService.AvatarBytes(fakeBytes, "image/webp")));

        buildService()
                .upsertUser(
                        "123", "TestUser", "test_user", "https://cdn.discordapp.com/avatars/123/hash.webp", GUILD_ID);

        ArgumentCaptor<GuildMember> captor = ArgumentCaptor.forClass(GuildMember.class);
        verify(memberRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isEqualTo(fakeBytes);
        assertThat(captor.getValue().getAvatarContentType()).isEqualTo("image/webp");
        assertThat(captor.getValue().getDisplayName()).isEqualTo("TestUser");
        assertThat(captor.getValue().getGuildId()).isEqualTo(GUILD_ID);
    }

    @Test
    void upsertUser_savesWithNullAvatar_whenDownloadFails() {
        when(avatarDownloadService.download(any())).thenReturn(Optional.empty());

        buildService()
                .upsertUser(
                        "123", "TestUser", "test_user", "https://cdn.discordapp.com/avatars/123/hash.webp", GUILD_ID);

        ArgumentCaptor<GuildMember> captor = ArgumentCaptor.forClass(GuildMember.class);
        verify(memberRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isNull();
        assertThat(captor.getValue().getAvatarContentType()).isNull();
    }

    @Test
    void upsertUser_savesWithNullAvatar_whenUrlIsNull() {
        buildService().upsertUser("123", "TestUser", "test_user", null, GUILD_ID);

        ArgumentCaptor<GuildMember> captor = ArgumentCaptor.forClass(GuildMember.class);
        verify(memberRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isNull();
        verify(avatarDownloadService, never()).download(any());
    }

    @Test
    void upsertUser_savesWithNullAvatar_whenUrlIsBlank() {
        buildService().upsertUser("123", "TestUser", "test_user", "   ", GUILD_ID);

        ArgumentCaptor<GuildMember> captor = ArgumentCaptor.forClass(GuildMember.class);
        verify(memberRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isNull();
        verify(avatarDownloadService, never()).download(any());
    }

    @Test
    void upsertUser_writesGlobalUsername() {
        buildService().upsertUser("123", "TestUser", "test_user", null, GUILD_ID);

        ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getSnowflake()).isEqualTo("123");
        assertThat(captor.getValue().getUsername()).isEqualTo("test_user");
    }

    @Test
    void upsertUser_doesNotOverwriteAvatarBytes_whenDownloadReturnsEmpty_andExistingMemberHasAvatar() {
        byte[] existing = new byte[] {9, 8, 7};
        GuildMember stored = new GuildMember(GUILD_ID, "123", "OldName", existing, "image/webp", Instant.now());
        when(memberRepository.findByGuildIdAndSnowflake(GUILD_ID, "123")).thenReturn(Optional.of(stored));
        when(avatarDownloadService.download(any())).thenReturn(Optional.empty());

        buildService().upsertUser("123", "NewName", "test_user", "https://cdn/x.webp", GUILD_ID);

        ArgumentCaptor<GuildMember> captor = ArgumentCaptor.forClass(GuildMember.class);
        verify(memberRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isEqualTo(existing);
        assertThat(captor.getValue().getDisplayName()).isEqualTo("NewName");
    }

    // ── getDisplayName ───────────────────────────────────────────────────────

    @Nested
    class GetDisplayNameFallback {

        @Test
        void returnsCachedName() {
            GuildMember member = new GuildMember(GUILD_ID, "111", "Alice", null, null, Instant.now());
            when(memberRepository.findByGuildIdAndSnowflake(GUILD_ID, "111")).thenReturn(Optional.of(member));

            assertThat(buildService().getDisplayName(GUILD_ID, "111")).isEqualTo("Alice");
        }

        @Test
        void fallsBackToGlobalUsername_whenMemberHasNoDisplayName() {
            GuildMember member = new GuildMember(GUILD_ID, "111", null, null, null, Instant.now());
            when(memberRepository.findByGuildIdAndSnowflake(GUILD_ID, "111")).thenReturn(Optional.of(member));
            when(cacheRepository.findById("111"))
                    .thenReturn(Optional.of(new DiscordUserCache("111", "alice_user", Instant.now())));

            assertThat(buildService().getDisplayName(GUILD_ID, "111")).isEqualTo("alice_user");
        }

        @Test
        void returnsUnknownUserWithSuffix_whenNoMemberAndNoUsername() {
            when(memberRepository.findByGuildIdAndSnowflake(GUILD_ID, "111223333"))
                    .thenReturn(Optional.empty());
            when(cacheRepository.findById("111223333")).thenReturn(Optional.empty());

            assertThat(buildService().getDisplayName(GUILD_ID, "111223333")).isEqualTo("Unknown User (#3333)");
        }

        @Test
        void returnsUnknownForNull() {
            assertThat(buildService().getDisplayName(GUILD_ID, null)).isEqualTo("Unknown User");
            verifyNoInteractions(memberRepository);
            verifyNoInteractions(cacheRepository);
        }

        @Test
        void returnsUnknownForBlank() {
            assertThat(buildService().getDisplayName(GUILD_ID, "  ")).isEqualTo("Unknown User");
            verifyNoInteractions(memberRepository);
            verifyNoInteractions(cacheRepository);
        }

        @Test
        void handlesShortSnowflake() {
            when(memberRepository.findByGuildIdAndSnowflake(GUILD_ID, "12")).thenReturn(Optional.empty());
            when(cacheRepository.findById("12")).thenReturn(Optional.empty());

            assertThat(buildService().getDisplayName(GUILD_ID, "12")).isEqualTo("Unknown User (#12)");
        }
    }

    // ── getDisplayNames (batch) ──────────────────────────────────────────────

    @Nested
    class GetDisplayNamesBatch {

        @Test
        void returnsBatchNames() {
            List<GuildMember> entries = List.of(
                    new GuildMember(GUILD_ID, "a1", "Alice", null, null, Instant.now()),
                    new GuildMember(GUILD_ID, "b2", "Bob", null, null, Instant.now()));
            when(memberRepository.findAllByGuildIdAndSnowflakeIn(GUILD_ID, Set.of("a1", "b2")))
                    .thenReturn(entries);

            Map<String, String> result = buildService().getDisplayNames(GUILD_ID, Set.of("a1", "b2"));

            assertThat(result).containsEntry("a1", "Alice").containsEntry("b2", "Bob");
        }

        @Test
        void fillsBlanksFromGlobalUsername() {
            when(memberRepository.findAllByGuildIdAndSnowflakeIn(GUILD_ID, Set.of("a1", "b2")))
                    .thenReturn(List.of(new GuildMember(GUILD_ID, "a1", "Alice", null, null, Instant.now())));
            when(cacheRepository.findAllBySnowflakeIn(Set.of("b2")))
                    .thenReturn(List.of(new DiscordUserCache("b2", "bob_global", Instant.now())));

            Map<String, String> result = buildService().getDisplayNames(GUILD_ID, Set.of("a1", "b2"));

            assertThat(result).containsEntry("a1", "Alice").containsEntry("b2", "bob_global");
        }

        @Test
        void emptyForNull() {
            assertThat(buildService().getDisplayNames(GUILD_ID, null)).isEmpty();
            verifyNoInteractions(memberRepository);
        }

        @Test
        void filtersNullAndBlank() {
            when(memberRepository.findAllByGuildIdAndSnowflakeIn(GUILD_ID, Set.of("valid")))
                    .thenReturn(List.of(new GuildMember(GUILD_ID, "valid", "Valid", null, null, Instant.now())));

            Map<String, String> result = buildService().getDisplayNames(GUILD_ID, Arrays.asList("valid", null, "  "));

            assertThat(result).containsOnlyKeys("valid");
        }
    }
}
