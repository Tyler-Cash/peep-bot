package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.model.DiscordUserCache;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DiscordUserCacheServiceTest {

    private DiscordUserCacheRepository cacheRepository;
    private AttendanceRepository attendanceRepository;
    private EventRepository eventRepository;
    private DiscordService discordService;
    private DiscordConfiguration discordConfiguration;
    private DiscordUserCacheService service;

    @BeforeEach
    void setUp() {
        cacheRepository = mock(DiscordUserCacheRepository.class);
        attendanceRepository = mock(AttendanceRepository.class);
        eventRepository = mock(EventRepository.class);
        discordService = mock(DiscordService.class);
        discordConfiguration = mock(DiscordConfiguration.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DiscordService> discordServiceProvider = mock(ObjectProvider.class);
        lenient().when(discordServiceProvider.getObject()).thenReturn(discordService);
        lenient().when(eventRepository.findAllDistinctCreatorSnowflakes()).thenReturn(List.of());
        service = new DiscordUserCacheService(
                cacheRepository, attendanceRepository, eventRepository, discordServiceProvider, discordConfiguration);
    }

    @Nested
    @DisplayName("getDisplayName fallback (item 14)")
    class GetDisplayNameFallback {

        @Test
        @DisplayName("returns cached display name when found")
        void returnsCachedName() {
            when(cacheRepository.findById("12345"))
                    .thenReturn(Optional.of(new DiscordUserCache("12345", "Alice", Instant.now())));

            assertThat(service.getDisplayName("12345")).isEqualTo("Alice");
        }

        @Test
        @DisplayName("returns 'Unknown User (#XXXX)' when not cached")
        void returnsUnknownUserWithSuffix() {
            when(cacheRepository.findById("123456789")).thenReturn(Optional.empty());

            String result = service.getDisplayName("123456789");

            assertThat(result).isEqualTo("Unknown User (#6789)");
        }

        @Test
        @DisplayName("returns 'Unknown User' for null snowflake")
        void returnsUnknownForNull() {
            assertThat(service.getDisplayName(null)).isEqualTo("Unknown User");
        }

        @Test
        @DisplayName("returns 'Unknown User' for blank snowflake")
        void returnsUnknownForBlank() {
            assertThat(service.getDisplayName("  ")).isEqualTo("Unknown User");
        }

        @Test
        @DisplayName("handles short snowflake (fewer than 4 chars)")
        void handlesShortSnowflake() {
            when(cacheRepository.findById("12")).thenReturn(Optional.empty());

            assertThat(service.getDisplayName("12")).isEqualTo("Unknown User (#12)");
        }
    }

    @Nested
    @DisplayName("getDisplayNames batch lookup")
    class GetDisplayNamesBatch {

        @Test
        @DisplayName("returns map of cached names")
        void returnsBatchNames() {
            when(cacheRepository.findAllBySnowflakeIn(Set.of("1", "2")))
                    .thenReturn(List.of(
                            new DiscordUserCache("1", "Alice", Instant.now()),
                            new DiscordUserCache("2", "Bob", Instant.now())));

            Map<String, String> result = service.getDisplayNames(List.of("1", "2"));

            assertThat(result).containsEntry("1", "Alice").containsEntry("2", "Bob");
        }

        @Test
        @DisplayName("returns empty map for null input")
        void emptyForNull() {
            assertThat(service.getDisplayNames(null)).isEmpty();
        }

        @Test
        @DisplayName("filters out null and blank snowflakes")
        void filtersNullAndBlank() {
            when(cacheRepository.findAllBySnowflakeIn(Set.of("1")))
                    .thenReturn(List.of(new DiscordUserCache("1", "Alice", Instant.now())));

            Map<String, String> result = service.getDisplayNames(Arrays.asList("1", null, "", "  "));

            assertThat(result).hasSize(1).containsEntry("1", "Alice");
        }
    }

    @Nested
    @DisplayName("refreshStaleEntries (item 7)")
    class RefreshStaleEntries {

        @Test
        @DisplayName("refreshes stale cache entries from Discord API")
        void refreshesStaleEntries() {
            long guildId = 999L;
            when(discordConfiguration.getGuildId()).thenReturn(guildId);
            when(attendanceRepository.findAllDistinctSnowflakes()).thenReturn(List.of("111", "222"));

            // 111 is stale, 222 is fresh
            Instant staleTime = Instant.now().minus(60, ChronoUnit.MINUTES);
            Instant freshTime = Instant.now();
            when(cacheRepository.findAllBySnowflakeIn(List.of("111", "222")))
                    .thenReturn(List.of(
                            new DiscordUserCache("111", "OldName", staleTime),
                            new DiscordUserCache("222", "FreshName", freshTime)));

            Member member = mock(Member.class);
            when(member.getNickname()).thenReturn("NewName");
            when(discordService.getMemberFromServer(guildId, 111L)).thenReturn(member);

            service.refreshStaleEntries();

            ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
            verify(cacheRepository).save(captor.capture());
            assertThat(captor.getValue().getSnowflake()).isEqualTo("111");
            assertThat(captor.getValue().getDisplayName()).isEqualTo("NewName");

            // Fresh entry should not be refreshed
            verify(discordService, never()).getMemberFromServer(guildId, 222L);
        }

        @Test
        @DisplayName("refreshes entries missing from cache")
        void refreshesMissingEntries() {
            long guildId = 999L;
            when(discordConfiguration.getGuildId()).thenReturn(guildId);
            when(attendanceRepository.findAllDistinctSnowflakes()).thenReturn(List.of("333"));
            when(cacheRepository.findAllBySnowflakeIn(List.of("333"))).thenReturn(List.of());

            Member member = mock(Member.class);
            when(member.getNickname()).thenReturn("BrandNew");
            when(discordService.getMemberFromServer(guildId, 333L)).thenReturn(member);

            service.refreshStaleEntries();

            ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
            verify(cacheRepository).save(captor.capture());
            assertThat(captor.getValue().getSnowflake()).isEqualTo("333");
            assertThat(captor.getValue().getDisplayName()).isEqualTo("BrandNew");
        }

        @Test
        @DisplayName("skips refresh when no active snowflakes")
        void skipsWhenNoActiveSnowflakes() {
            when(attendanceRepository.findAllDistinctSnowflakes()).thenReturn(List.of());

            service.refreshStaleEntries();

            verifyNoInteractions(cacheRepository);
            verifyNoInteractions(discordService);
        }

        @Test
        @DisplayName("handles Discord API failure gracefully")
        void handlesApiFailure() {
            long guildId = 999L;
            when(discordConfiguration.getGuildId()).thenReturn(guildId);
            when(attendanceRepository.findAllDistinctSnowflakes()).thenReturn(List.of("444"));
            when(cacheRepository.findAllBySnowflakeIn(List.of("444"))).thenReturn(List.of());
            when(discordService.getMemberFromServer(guildId, 444L)).thenThrow(new RuntimeException("API down"));

            // Should not throw
            service.refreshStaleEntries();

            // Cache should not be updated
            verify(cacheRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("upsertUser")
    class UpsertUser {

        @Test
        @DisplayName("saves a cache entry with current timestamp")
        void savesEntry() {
            service.upsertUser("555", "TestUser");

            ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
            verify(cacheRepository).save(captor.capture());
            assertThat(captor.getValue().getSnowflake()).isEqualTo("555");
            assertThat(captor.getValue().getDisplayName()).isEqualTo("TestUser");
            assertThat(captor.getValue().getUpdatedAt()).isNotNull();
        }
    }
}
