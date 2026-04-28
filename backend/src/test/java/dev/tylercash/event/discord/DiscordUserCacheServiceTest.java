package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.model.DiscordUserCache;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.ImageProxy;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DiscordUserCacheServiceTest {

    @Mock
    DiscordUserCacheRepository cacheRepository;

    @Mock
    AttendanceRepository attendanceRepository;

    @Mock
    EventRepository eventRepository;

    @Mock
    ObjectProvider<DiscordService> discordServiceProvider;

    @Mock
    DiscordConfiguration discordConfiguration;

    @Mock
    AvatarDownloadService avatarDownloadService;

    DiscordUserCacheService buildService() {
        return new DiscordUserCacheService(
                cacheRepository,
                attendanceRepository,
                eventRepository,
                discordServiceProvider,
                discordConfiguration,
                avatarDownloadService);
    }

    // ── upsertUser ──────────────────────────────────────────────────────────

    @Test
    void upsertUser_downloadsAndStoresAvatar_whenUrlProvided() {
        byte[] fakeBytes = new byte[] {1, 2, 3};
        when(avatarDownloadService.download("https://cdn.discordapp.com/avatars/123/hash.webp"))
                .thenReturn(Optional.of(new AvatarDownloadService.AvatarBytes(fakeBytes, "image/webp")));

        buildService()
                .upsertUser("123", "TestUser", "test_user", "https://cdn.discordapp.com/avatars/123/hash.webp", 0L);

        ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isEqualTo(fakeBytes);
        assertThat(captor.getValue().getAvatarContentType()).isEqualTo("image/webp");
    }

    @Test
    void upsertUser_savesWithNullAvatar_whenDownloadFails() {
        when(avatarDownloadService.download(any())).thenReturn(Optional.empty());

        buildService()
                .upsertUser("123", "TestUser", "test_user", "https://cdn.discordapp.com/avatars/123/hash.webp", 0L);

        ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isNull();
        assertThat(captor.getValue().getAvatarContentType()).isNull();
    }

    @Test
    void upsertUser_savesWithNullAvatar_whenUrlIsNull() {
        buildService().upsertUser("123", "TestUser", "test_user", null, 0L);

        ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isNull();
        verify(avatarDownloadService, never()).download(any());
    }

    @Test
    void upsertUser_savesWithNullAvatar_whenUrlIsBlank() {
        buildService().upsertUser("123", "TestUser", "test_user", "   ", 0L);

        ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isNull();
        verify(avatarDownloadService, never()).download(any());
    }

    // ── getDisplayName ───────────────────────────────────────────────────────

    @Nested
    class GetDisplayNameFallback {

        @Test
        void returnsCachedName() {
            DiscordUserCache cached = new DiscordUserCache(
                    "111", "Alice", "alice_user", Instant.now(), null, null, java.util.Collections.emptySet());
            when(cacheRepository.findById("111")).thenReturn(Optional.of(cached));

            assertThat(buildService().getDisplayName("111")).isEqualTo("Alice");
        }

        @Test
        void returnsUnknownUserWithSuffix() {
            when(cacheRepository.findById("111223333")).thenReturn(Optional.empty());

            assertThat(buildService().getDisplayName("111223333")).isEqualTo("Unknown User (#3333)");
        }

        @Test
        void returnsUnknownForNull() {
            assertThat(buildService().getDisplayName(null)).isEqualTo("Unknown User");
            verifyNoInteractions(cacheRepository);
        }

        @Test
        void returnsUnknownForBlank() {
            assertThat(buildService().getDisplayName("  ")).isEqualTo("Unknown User");
            verifyNoInteractions(cacheRepository);
        }

        @Test
        void handlesShortSnowflake() {
            when(cacheRepository.findById("12")).thenReturn(Optional.empty());

            assertThat(buildService().getDisplayName("12")).isEqualTo("Unknown User (#12)");
        }
    }

    // ── getDisplayNames (batch) ──────────────────────────────────────────────

    @Nested
    class GetDisplayNamesBatch {

        @Test
        void returnsBatchNames() {
            List<DiscordUserCache> entries = List.of(
                    new DiscordUserCache(
                            "a1", "Alice", "alice_u", Instant.now(), null, null, java.util.Collections.emptySet()),
                    new DiscordUserCache(
                            "b2", "Bob", "bob_u", Instant.now(), null, null, java.util.Collections.emptySet()));
            when(cacheRepository.findAllBySnowflakeIn(Set.of("a1", "b2"))).thenReturn(entries);

            Map<String, String> result = buildService().getDisplayNames(Set.of("a1", "b2"));

            assertThat(result).containsEntry("a1", "Alice").containsEntry("b2", "Bob");
        }

        @Test
        void emptyForNull() {
            assertThat(buildService().getDisplayNames(null)).isEmpty();
            verifyNoInteractions(cacheRepository);
        }

        @Test
        void filtersNullAndBlank() {
            List<DiscordUserCache> entries = List.of(new DiscordUserCache(
                    "valid", "Valid", "valid_u", Instant.now(), null, null, java.util.Collections.emptySet()));
            when(cacheRepository.findAllBySnowflakeIn(Set.of("valid"))).thenReturn(entries);

            Map<String, String> result = buildService().getDisplayNames(Arrays.asList("valid", null, "  "));

            assertThat(result).containsOnlyKeys("valid");
        }
    }

    // ── refreshStaleEntries ──────────────────────────────────────────────────

    @Nested
    class RefreshStaleEntries {

        @Test
        void refreshesStaleEntries() {
            long guildId = 999L;
            String snowflake = "123456789012345678";
            long snowflakeLong = Long.parseLong(snowflake);
            when(discordConfiguration.getGuildId()).thenReturn(guildId);
            when(attendanceRepository.findAllDistinctSnowflakes()).thenReturn(List.of(snowflake));
            when(eventRepository.findAllDistinctCreatorSnowflakes()).thenReturn(List.of());

            DiscordUserCache stale = new DiscordUserCache(
                    snowflake,
                    "OldName",
                    "old_u",
                    Instant.now().minus(60, ChronoUnit.MINUTES),
                    null,
                    null,
                    new java.util.HashSet<>(List.of(guildId)));
            when(cacheRepository.findAllBySnowflakeIn(List.of(snowflake))).thenReturn(List.of(stale));

            DiscordService discordService = mock(DiscordService.class);
            Member member = mock(Member.class);
            net.dv8tion.jda.api.entities.User jdaUser = mock(net.dv8tion.jda.api.entities.User.class);
            when(member.getUser()).thenReturn(jdaUser);
            when(jdaUser.getName()).thenReturn("new_u");
            when(discordServiceProvider.getObject()).thenReturn(discordService);
            when(discordService.getMemberFromServer(guildId, snowflakeLong)).thenReturn(member);
            when(member.getNickname()).thenReturn("NewName");
            when(member.getEffectiveName()).thenReturn("NewName");
            ImageProxy avatarProxy = mock(ImageProxy.class);
            when(member.getEffectiveAvatar()).thenReturn(avatarProxy);
            when(avatarProxy.getUrl(256)).thenReturn("https://cdn.discordapp.com/avatars/" + snowflake + "/abc.webp");
            when(avatarDownloadService.download(any())).thenReturn(Optional.empty());

            buildService().refreshStaleEntries();

            verify(cacheRepository).save(any(DiscordUserCache.class));
        }

        @Test
        void skipsWhenNoActiveSnowflakes() {
            when(attendanceRepository.findAllDistinctSnowflakes()).thenReturn(List.of());
            when(eventRepository.findAllDistinctCreatorSnowflakes()).thenReturn(List.of());

            buildService().refreshStaleEntries();

            verifyNoInteractions(cacheRepository);
        }

        @Test
        void handlesApiFailure() {
            long guildId = 999L;
            String snowflake = "987654321098765432";
            when(discordConfiguration.getGuildId()).thenReturn(guildId);
            when(attendanceRepository.findAllDistinctSnowflakes()).thenReturn(List.of(snowflake));
            when(eventRepository.findAllDistinctCreatorSnowflakes()).thenReturn(List.of());

            DiscordUserCache stale = new DiscordUserCache(
                    snowflake,
                    "OldName",
                    "old_u",
                    Instant.now().minus(60, ChronoUnit.MINUTES),
                    null,
                    null,
                    java.util.Collections.emptySet());
            when(cacheRepository.findAllBySnowflakeIn(List.of(snowflake))).thenReturn(List.of(stale));

            DiscordService discordService = mock(DiscordService.class);
            when(discordServiceProvider.getObject()).thenReturn(discordService);
            when(discordService.getMemberFromServer(anyLong(), anyLong()))
                    .thenThrow(new RuntimeException("Discord API error"));

            buildService().refreshStaleEntries();

            verify(cacheRepository, never()).save(any());
        }
    }
}
