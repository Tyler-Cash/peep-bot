package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.mockito.ArgumentCaptor;
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

    private static Member mockMember(String snowflake, String displayName, String username) {
        Member member = mock(Member.class);
        net.dv8tion.jda.api.entities.User jdaUser = mock(net.dv8tion.jda.api.entities.User.class);
        lenient().when(member.getIdLong()).thenReturn(Long.parseLong(snowflake));
        lenient().when(member.getUser()).thenReturn(jdaUser);
        lenient().when(jdaUser.getName()).thenReturn(username);
        lenient().when(member.getNickname()).thenReturn(displayName);
        lenient().when(member.getEffectiveName()).thenReturn(displayName);
        ImageProxy avatarProxy = mock(ImageProxy.class);
        lenient().when(member.getEffectiveAvatar()).thenReturn(avatarProxy);
        lenient()
                .when(avatarProxy.getUrl(256))
                .thenReturn("https://cdn.discordapp.com/avatars/" + snowflake + "/abc.webp");
        return member;
    }

    @Test
    void refreshesStaleEntries() {
        String snowflake = "123456789012345678";
        when(memberRepository.findStaleOrMissing(any(Instant.class), anyInt()))
                .thenReturn(List.of(ref(GUILD_ID, snowflake)));

        DiscordService discordService = mock(DiscordService.class);
        when(discordServiceProvider.getObject()).thenReturn(discordService);
        Member member = mockMember(snowflake, "NewName", "new_u");
        when(discordService.getMembersFromServer(eq(GUILD_ID), any(long[].class)))
                .thenReturn(List.of(member));

        buildJob().refreshStaleEntries();

        verify(cacheService).upsertUser(eq(snowflake), eq("NewName"), eq("new_u"), anyString(), eq(GUILD_ID));
    }

    @Test
    void batchesAllSnowflakesForOneGuildIntoSingleGatewayRequest() {
        String s1 = "1111111111111111111";
        String s2 = "2222222222222222222";
        when(memberRepository.findStaleOrMissing(any(Instant.class), anyInt()))
                .thenReturn(List.of(ref(GUILD_ID, s1), ref(GUILD_ID, s2)));

        Member m1 = mockMember(s1, "A", "a");
        Member m2 = mockMember(s2, "B", "b");
        DiscordService discordService = mock(DiscordService.class);
        when(discordServiceProvider.getObject()).thenReturn(discordService);
        ArgumentCaptor<long[]> idsCaptor = ArgumentCaptor.forClass(long[].class);
        when(discordService.getMembersFromServer(eq(GUILD_ID), idsCaptor.capture()))
                .thenReturn(List.of(m1, m2));

        buildJob().refreshStaleEntries();

        verify(discordService, times(1)).getMembersFromServer(eq(GUILD_ID), any(long[].class));
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(Long.parseLong(s1), Long.parseLong(s2));
        verify(cacheService).upsertUser(eq(s1), eq("A"), eq("a"), anyString(), eq(GUILD_ID));
        verify(cacheService).upsertUser(eq(s2), eq("B"), eq("b"), anyString(), eq(GUILD_ID));
    }

    @Test
    void skipsMembersMissingFromBulkResult() {
        String present = "1111111111111111111";
        String absent = "2222222222222222222";
        when(memberRepository.findStaleOrMissing(any(Instant.class), anyInt()))
                .thenReturn(List.of(ref(GUILD_ID, present), ref(GUILD_ID, absent)));

        Member presentMember = mockMember(present, "P", "p");
        DiscordService discordService = mock(DiscordService.class);
        when(discordServiceProvider.getObject()).thenReturn(discordService);
        when(discordService.getMembersFromServer(eq(GUILD_ID), any(long[].class)))
                .thenReturn(List.of(presentMember));

        buildJob().refreshStaleEntries();

        verify(cacheService).upsertUser(eq(present), any(), any(), any(), eq(GUILD_ID));
        verify(cacheService, never()).upsertUser(eq(absent), any(), any(), any(), anyLong());
    }

    @Test
    void skipsWhenNothingStale() {
        when(memberRepository.findStaleOrMissing(any(Instant.class), anyInt())).thenReturn(List.of());

        buildJob().refreshStaleEntries();

        verifyNoInteractions(cacheService);
        verifyNoInteractions(discordServiceProvider);
    }

    @Test
    void handlesBulkLookupFailure() {
        String snowflake = "987654321098765432";
        when(memberRepository.findStaleOrMissing(any(Instant.class), anyInt()))
                .thenReturn(List.of(ref(GUILD_ID, snowflake)));

        DiscordService discordService = mock(DiscordService.class);
        when(discordServiceProvider.getObject()).thenReturn(discordService);
        when(discordService.getMembersFromServer(eq(GUILD_ID), any(long[].class)))
                .thenThrow(new RuntimeException("Discord Gateway error"));

        buildJob().refreshStaleEntries();

        verify(cacheService, never()).upsertUser(any(), any(), any(), any(), anyLong());
    }
}
