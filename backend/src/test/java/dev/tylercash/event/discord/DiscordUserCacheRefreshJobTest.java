package dev.tylercash.event.discord;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.GuildMemberRepository;
import dev.tylercash.event.db.repository.GuildMemberRepository.StaleMemberRef;
import java.time.Instant;
import java.util.List;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.ImageProxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DiscordUserCacheRefreshJobTest {

    private static final long GUILD_ID = 999L;

    @Mock
    DiscordUserCacheService cacheService;

    @Mock
    GuildMemberRepository memberRepository;

    @Mock
    ObjectProvider<DiscordService> discordServiceProvider;

    DiscordUserCacheRefreshJob buildJob() {
        return new DiscordUserCacheRefreshJob(cacheService, memberRepository, discordServiceProvider);
    }

    private static StaleMemberRef ref(long guildId, String snowflake) {
        return new StaleMemberRef() {
            @Override
            public long getGuildId() {
                return guildId;
            }

            @Override
            public String getSnowflake() {
                return snowflake;
            }
        };
    }

    @Test
    void refreshesStaleEntries() {
        String snowflake = "123456789012345678";
        long snowflakeLong = Long.parseLong(snowflake);
        when(memberRepository.findStaleOrMissing(any(Instant.class), anyInt()))
                .thenReturn(List.of(ref(GUILD_ID, snowflake)));

        DiscordService discordService = mock(DiscordService.class);
        Member member = mock(Member.class);
        net.dv8tion.jda.api.entities.User jdaUser = mock(net.dv8tion.jda.api.entities.User.class);
        when(member.getUser()).thenReturn(jdaUser);
        when(jdaUser.getName()).thenReturn("new_u");
        when(discordServiceProvider.getObject()).thenReturn(discordService);
        when(discordService.getMemberFromServer(GUILD_ID, snowflakeLong)).thenReturn(member);
        when(member.getNickname()).thenReturn("NewName");
        when(member.getEffectiveName()).thenReturn("NewName");
        ImageProxy avatarProxy = mock(ImageProxy.class);
        when(member.getEffectiveAvatar()).thenReturn(avatarProxy);
        when(avatarProxy.getUrl(256)).thenReturn("https://cdn.discordapp.com/avatars/" + snowflake + "/abc.webp");

        buildJob().refreshStaleEntries();

        verify(cacheService).upsertUser(eq(snowflake), eq("NewName"), eq("new_u"), anyString(), eq(GUILD_ID));
    }

    @Test
    void skipsWhenNothingStale() {
        when(memberRepository.findStaleOrMissing(any(Instant.class), anyInt())).thenReturn(List.of());

        buildJob().refreshStaleEntries();

        verifyNoInteractions(cacheService);
        verifyNoInteractions(discordServiceProvider);
    }

    @Test
    void handlesApiFailure() {
        String snowflake = "987654321098765432";
        when(memberRepository.findStaleOrMissing(any(Instant.class), anyInt()))
                .thenReturn(List.of(ref(GUILD_ID, snowflake)));

        DiscordService discordService = mock(DiscordService.class);
        when(discordServiceProvider.getObject()).thenReturn(discordService);
        when(discordService.getMemberFromServer(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("Discord API error"));

        buildJob().refreshStaleEntries();

        verify(cacheService, never()).upsertUser(any(), any(), any(), any(), anyLong());
    }
}
