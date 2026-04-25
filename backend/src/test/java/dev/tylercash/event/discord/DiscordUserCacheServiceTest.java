package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.model.DiscordUserCache;
import java.util.Optional;
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

    @Test
    void upsertUser_downloadsAndStoresAvatar_whenUrlProvided() {
        byte[] fakeBytes = new byte[] {1, 2, 3};
        when(avatarDownloadService.download("https://cdn.discordapp.com/avatars/123/hash.webp"))
                .thenReturn(Optional.of(new AvatarDownloadService.AvatarBytes(fakeBytes, "image/webp")));

        DiscordUserCacheService service = new DiscordUserCacheService(
                cacheRepository,
                attendanceRepository,
                eventRepository,
                discordServiceProvider,
                discordConfiguration,
                avatarDownloadService);

        service.upsertUser("123", "TestUser", "https://cdn.discordapp.com/avatars/123/hash.webp");

        ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isEqualTo(fakeBytes);
        assertThat(captor.getValue().getAvatarContentType()).isEqualTo("image/webp");
    }

    @Test
    void upsertUser_savesWithNullAvatar_whenDownloadFails() {
        when(avatarDownloadService.download(any())).thenReturn(Optional.empty());

        DiscordUserCacheService service = new DiscordUserCacheService(
                cacheRepository,
                attendanceRepository,
                eventRepository,
                discordServiceProvider,
                discordConfiguration,
                avatarDownloadService);

        service.upsertUser("123", "TestUser", "https://cdn.discordapp.com/avatars/123/hash.webp");

        ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isNull();
        assertThat(captor.getValue().getAvatarContentType()).isNull();
    }

    @Test
    void upsertUser_savesWithNullAvatar_whenUrlIsNull() {
        DiscordUserCacheService service = new DiscordUserCacheService(
                cacheRepository,
                attendanceRepository,
                eventRepository,
                discordServiceProvider,
                discordConfiguration,
                avatarDownloadService);

        service.upsertUser("123", "TestUser", null);

        ArgumentCaptor<DiscordUserCache> captor = ArgumentCaptor.forClass(DiscordUserCache.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarBytes()).isNull();
        verify(avatarDownloadService, never()).download(any());
    }
}
