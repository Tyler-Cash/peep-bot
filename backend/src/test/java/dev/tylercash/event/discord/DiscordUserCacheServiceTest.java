package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.DiscordGuildMemberRepository;
import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.model.DiscordGuildMember;
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

    private static final long GUILD = 999L;

    @Mock
    DiscordUserCacheRepository cacheRepository;

    @Mock
    DiscordGuildMemberRepository memberRepository;

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
                memberRepository,
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
                .upsertUser("123", "TestUser", "test_user", "https://cdn.discordapp.com/avatars/123/hash.webp", GUILD);

        ArgumentCaptor<DiscordGuildMember> captor = ArgumentCaptor.forClass(DiscordGuildMember.class);
        verify(memberRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isEqualTo(fakeBytes);
        assertThat(captor.getValue().getAvatarContentType()).isEqualTo("image/webp");
        assertThat(captor.getValue().getDisplayName()).isEqualTo("TestUser");
    }

    @Test
    void upsertUser_keepsExistingAvatar_whenDownloadFails() {
        when(avatarDownloadService.download(any())).thenReturn(Optional.empty());

        buildService()
                .upsertUser("123", "TestUser", "test_user", "https://cdn.discordapp.com/avatars/123/hash.webp", GUILD);

        ArgumentCaptor<DiscordGuildMember> captor = ArgumentCaptor.forClass(DiscordGuildMember.class);
        verify(memberRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isNull();
    }

    @Test
    void upsertUser_savesWithNullAvatar_whenUrlIsNull() {
        buildService().upsertUser("123", "TestUser", "test_user", null, GUILD);

        ArgumentCaptor<DiscordGuildMember> captor = ArgumentCaptor.forClass(DiscordGuildMember.class);
        verify(memberRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isNull();
        verify(avatarDownloadService, never()).download(any());
    }

    @Test
    void upsertUser_savesWithNullAvatar_whenUrlIsBlank() {
        buildService().upsertUser("123", "TestUser", "test_user", "   ", GUILD);

        ArgumentCaptor<DiscordGuildMember> captor = ArgumentCaptor.forClass(DiscordGuildMember.class);
        verify(memberRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isNull();
        verify(avatarDownloadService, never()).download(any());
    }

    @Test
    void upsertUser_writesGlobalUsernameRow() {
        buildService().upsertUser("123", "TestUser", "test_user", null, GUILD);

        ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("test_user");
        assertThat(captor.getValue().getSnowflake()).isEqualTo("123");
    }

    // ── getDisplayName ───────────────────────────────────────────────────────

    @Nested
    class GetDisplayNameFallback {

        @Test
        void returnsGuildNickname() {
            DiscordGuildMember member = new DiscordGuildMember(GUILD, "111", "Alice", null, null, Instant.now());
            when(memberRepository.findByGuildIdAndSnowflake(GUILD, "111")).thenReturn(Optional.of(member));

            assertThat(buildService().getDisplayName(GUILD, "111")).isEqualTo("Alice");
        }

        @Test
        void fallsBackToGlobalUsername() {
            when(memberRepository.findByGuildIdAndSnowflake(GUILD, "111")).thenReturn(Optional.empty());
            when(cacheRepository.findById("111"))
                    .thenReturn(Optional.of(new DiscordUserCache("111", "alice_user", Instant.now())));

            assertThat(buildService().getDisplayName(GUILD, "111")).isEqualTo("alice_user");
        }

        @Test
        void returnsUnknownUserWithSuffix() {
            when(memberRepository.findByGuildIdAndSnowflake(GUILD, "111223333")).thenReturn(Optional.empty());
            when(cacheRepository.findById("111223333")).thenReturn(Optional.empty());

            assertThat(buildService().getDisplayName(GUILD, "111223333")).isEqualTo("Unknown User (#3333)");
        }

        @Test
        void returnsUnknownForNull() {
            assertThat(buildService().getDisplayName(GUILD, null)).isEqualTo("Unknown User");
            verifyNoInteractions(memberRepository);
        }

        @Test
        void returnsUnknownForBlank() {
            assertThat(buildService().getDisplayName(GUILD, "  ")).isEqualTo("Unknown User");
            verifyNoInteractions(memberRepository);
        }

        @Test
        void handlesShortSnowflake() {
            when(memberRepository.findByGuildIdAndSnowflake(GUILD, "12")).thenReturn(Optional.empty());
            when(cacheRepository.findById("12")).thenReturn(Optional.empty());

            assertThat(buildService().getDisplayName(GUILD, "12")).isEqualTo("Unknown User (#12)");
        }
    }

    // ── getDisplayNames (batch) ──────────────────────────────────────────────

    @Nested
    class GetDisplayNamesBatch {

        @Test
        void returnsBatchNames() {
            List<DiscordGuildMember> members = List.of(
                    new DiscordGuildMember(GUILD, "a1", "Alice", null, null, Instant.now()),
                    new DiscordGuildMember(GUILD, "b2", "Bob", null, null, Instant.now()));
            when(memberRepository.findAllByGuildIdAndSnowflakeIn(eq(GUILD), any()))
                    .thenReturn(members);

            Map<String, String> result = buildService().getDisplayNames(GUILD, Set.of("a1", "b2"));

            assertThat(result).containsEntry("a1", "Alice").containsEntry("b2", "Bob");
        }

        @Test
        void emptyForNull() {
            assertThat(buildService().getDisplayNames(GUILD, null)).isEmpty();
            verifyNoInteractions(memberRepository);
        }

        @Test
        void filtersNullAndBlank() {
            List<DiscordGuildMember> members =
                    List.of(new DiscordGuildMember(GUILD, "valid", "Valid", null, null, Instant.now()));
            when(memberRepository.findAllByGuildIdAndSnowflakeIn(eq(GUILD), eq(Set.of("valid"))))
                    .thenReturn(members);

            Map<String, String> result = buildService().getDisplayNames(GUILD, Arrays.asList("valid", null, "  "));

            assertThat(result).containsOnlyKeys("valid");
        }
    }

    // ── refreshStaleEntries ──────────────────────────────────────────────────

    @Nested
    class RefreshStaleEntries {

        @Test
        void refreshesStaleEntries() {
            String snowflake = "123456789012345678";
            long snowflakeLong = Long.parseLong(snowflake);
            when(discordConfiguration.getGuildId()).thenReturn(GUILD);
            when(attendanceRepository.findAllDistinctSnowflakes()).thenReturn(List.of(snowflake));
            when(eventRepository.findAllDistinctCreatorSnowflakes()).thenReturn(List.of());

            DiscordGuildMember stale = new DiscordGuildMember(
                    GUILD, snowflake, "OldName", null, null, Instant.now().minus(60, ChronoUnit.MINUTES));
            when(memberRepository.findAllByGuildIdAndSnowflakeIn(eq(GUILD), any()))
                    .thenReturn(List.of(stale));

            DiscordService discordService = mock(DiscordService.class);
            Member member = mock(Member.class);
            net.dv8tion.jda.api.entities.User jdaUser = mock(net.dv8tion.jda.api.entities.User.class);
            when(member.getUser()).thenReturn(jdaUser);
            when(jdaUser.getName()).thenReturn("new_u");
            when(discordServiceProvider.getObject()).thenReturn(discordService);
            when(discordService.getMemberFromServer(GUILD, snowflakeLong)).thenReturn(member);
            when(member.getNickname()).thenReturn("NewName");
            when(member.getEffectiveName()).thenReturn("NewName");
            ImageProxy avatarProxy = mock(ImageProxy.class);
            when(member.getEffectiveAvatar()).thenReturn(avatarProxy);
            when(avatarProxy.getUrl(256)).thenReturn("https://cdn.discordapp.com/avatars/" + snowflake + "/abc.webp");
            when(avatarDownloadService.download(any())).thenReturn(Optional.empty());

            buildService().refreshStaleEntries();

            verify(memberRepository).save(any(DiscordGuildMember.class));
        }

        @Test
        void skipsWhenNoActiveSnowflakes() {
            when(attendanceRepository.findAllDistinctSnowflakes()).thenReturn(List.of());
            when(eventRepository.findAllDistinctCreatorSnowflakes()).thenReturn(List.of());

            buildService().refreshStaleEntries();

            verifyNoInteractions(memberRepository);
            verifyNoInteractions(cacheRepository);
        }

        @Test
        void handlesApiFailure() {
            String snowflake = "987654321098765432";
            when(discordConfiguration.getGuildId()).thenReturn(GUILD);
            when(attendanceRepository.findAllDistinctSnowflakes()).thenReturn(List.of(snowflake));
            when(eventRepository.findAllDistinctCreatorSnowflakes()).thenReturn(List.of());

            DiscordGuildMember stale = new DiscordGuildMember(
                    GUILD, snowflake, "OldName", null, null, Instant.now().minus(60, ChronoUnit.MINUTES));
            when(memberRepository.findAllByGuildIdAndSnowflakeIn(eq(GUILD), any()))
                    .thenReturn(List.of(stale));

            DiscordService discordService = mock(DiscordService.class);
            when(discordServiceProvider.getObject()).thenReturn(discordService);
            when(discordService.getMemberFromServer(anyLong(), anyLong()))
                    .thenThrow(new RuntimeException("Discord API error"));

            buildService().refreshStaleEntries();

            verify(memberRepository, never()).save(any());
        }
    }
}
