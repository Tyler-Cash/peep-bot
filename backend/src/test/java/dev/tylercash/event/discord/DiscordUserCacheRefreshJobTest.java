package dev.tylercash.event.discord;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.db.repository.GuildMemberRepository;
import dev.tylercash.event.discord.model.GuildMember;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
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
    AttendanceRepository attendanceRepository;

    @Mock
    EventRepository eventRepository;

    @Mock
    GuildRepository guildRepository;

    @Mock
    ObjectProvider<DiscordService> discordServiceProvider;

    DiscordUserCacheRefreshJob buildJob() {
        return new DiscordUserCacheRefreshJob(
                cacheService,
                memberRepository,
                attendanceRepository,
                eventRepository,
                guildRepository,
                discordServiceProvider);
    }

    @Test
    void refreshesStaleEntries() {
        String snowflake = "123456789012345678";
        long snowflakeLong = Long.parseLong(snowflake);
        when(guildRepository.findAllByActiveTrue()).thenReturn(List.of(Guild.withDefaults(GUILD_ID)));
        when(attendanceRepository.findAllDistinctSnowflakes()).thenReturn(List.of(snowflake));
        when(eventRepository.findAllDistinctCreatorSnowflakes()).thenReturn(List.of());

        GuildMember stale = new GuildMember(
                GUILD_ID, snowflake, "OldName", null, null, Instant.now().minus(60, ChronoUnit.MINUTES));
        when(memberRepository.findByGuildIdAndSnowflake(GUILD_ID, snowflake)).thenReturn(Optional.of(stale));

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
    void skipsWhenNoActiveSnowflakes() {
        when(attendanceRepository.findAllDistinctSnowflakes()).thenReturn(List.of());
        when(eventRepository.findAllDistinctCreatorSnowflakes()).thenReturn(List.of());

        buildJob().refreshStaleEntries();

        verifyNoInteractions(memberRepository);
        verifyNoInteractions(cacheService);
    }

    @Test
    void handlesApiFailure() {
        String snowflake = "987654321098765432";
        when(guildRepository.findAllByActiveTrue()).thenReturn(List.of(Guild.withDefaults(GUILD_ID)));
        when(attendanceRepository.findAllDistinctSnowflakes()).thenReturn(List.of(snowflake));
        when(eventRepository.findAllDistinctCreatorSnowflakes()).thenReturn(List.of());

        when(memberRepository.findByGuildIdAndSnowflake(GUILD_ID, snowflake)).thenReturn(Optional.empty());

        DiscordService discordService = mock(DiscordService.class);
        when(discordServiceProvider.getObject()).thenReturn(discordService);
        when(discordService.getMemberFromServer(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("Discord API error"));

        buildJob().refreshStaleEntries();

        verify(cacheService, never()).upsertUser(any(), any(), any(), any(), anyLong());
    }
}
