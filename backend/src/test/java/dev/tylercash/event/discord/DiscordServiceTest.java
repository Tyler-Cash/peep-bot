package dev.tylercash.event.discord;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.global.GoogleCalendarService;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.channel.ChannelCategory;
import org.javacord.api.entity.channel.RegularServerChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscordServiceTest {

    @Test
    void sortChannelsSuccess() {
        DiscordService discordService = new DiscordService(mock(DiscordConfiguration.class), mock(DiscordApi.class), mock(EventRepository.class), mock(GoogleCalendarService.class));
        ChannelCategory category = mock(ChannelCategory.class);
        RegularServerChannel regularServerChannel = mock(RegularServerChannel.class);
        when(category.getChannels()).thenReturn(new ArrayList<>(
                mock()
        ));
        discordService.sortChannels(category);
    }
}