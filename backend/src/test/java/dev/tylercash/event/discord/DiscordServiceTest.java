package dev.tylercash.event.discord;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.global.GoogleCalendarService;
import dev.tylercash.event.global.MetricsService;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.channel.ChannelCategory;
import org.javacord.api.entity.channel.RegularServerChannel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.mockito.Mockito.*;

class DiscordServiceTest {

    private static RegularServerChannel getChannelMock(String name) {
        RegularServerChannel channel = mock(RegularServerChannel.class);
        when(channel.getName()).thenReturn(name);
        return channel;
    }

    @Test
    void sortChannelsSuccess() {
        DiscordConfiguration discordConfiguration = mock(DiscordConfiguration.class);
        DiscordService discordService = new DiscordService(discordConfiguration, mock(DiscordApi.class), mock(EventRepository.class), mock(GoogleCalendarService.class), mock(MetricsService.class), mock(Clock.class));
        ChannelCategory category = mock(ChannelCategory.class);
        String seperatorChannel = "memories";
        List<RegularServerChannel> channels = List.of(
                getChannelMock("planning"),
                getChannelMock(seperatorChannel),
                getChannelMock("5th-jan-test-event-1"),
                getChannelMock("3rd-jan-test-event-1"),
                getChannelMock("2nd-jan-test-event-1")
        );
        when(discordConfiguration.getSeperatorChannel()).thenReturn(seperatorChannel);
        when(category.getChannels()).thenReturn(channels);
        discordService.sortChannels(category);

        verify(channels.get(0), times(1)).updateRawPosition(0);
        verify(channels.get(1), times(1)).updateRawPosition(1);
        verify(channels.get(2), times(1)).updateRawPosition(4);
        verify(channels.get(3), times(1)).updateRawPosition(3);
        verify(channels.get(4), times(1)).updateRawPosition(2);
    }
}