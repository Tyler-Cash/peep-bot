package dev.tylercash.event.discord;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.global.GoogleCalendarService;
import dev.tylercash.event.global.MetricsService;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.channel.ChannelCategory;
import org.javacord.api.entity.channel.RegularServerChannel;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.mention.AllowedMentionType;
import org.javacord.api.entity.message.mention.AllowedMentions;
import org.javacord.api.entity.permission.Role;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class DiscordServiceTest {

    private static RegularServerChannel getChannelMock(String name) {
        RegularServerChannel channel = mock(RegularServerChannel.class);
        when(channel.getName()).thenReturn(name);
        return channel;
    }

    private static @NotNull DiscordServiceTest.MockedDiscordService getGetMockedDiscordService() {
        DiscordConfiguration discordConfiguration = mock(DiscordConfiguration.class);
        DiscordApi discordApi = mock(DiscordApi.class);
        EventRepository eventRepository = mock(EventRepository.class);
        GoogleCalendarService googleCalendarService = mock(GoogleCalendarService.class);
        MetricsService metricsService = mock(MetricsService.class);
        Clock clock = mock(Clock.class);
        DiscordService discordService = new DiscordService(discordConfiguration, discordApi, eventRepository, googleCalendarService, metricsService, clock);
        MockedDiscordService result = new MockedDiscordService(discordConfiguration, discordApi, discordService);
        return result;
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

    @Test
    void getMessageBuilderMentioningRoles() {
        Role role = mock(Role.class);

        String testMessage = " test message";
        when(role.getMentionTag()).thenReturn("@test-event-tag");

        MessageBuilder messageBuilder = DiscordService.getMessageBuilderMentioningRoles(testMessage, Set.of(role));

        assertEquals(role.getMentionTag() + testMessage, messageBuilder.getStringBuilder().toString());
    }

    @Test
    void getAllowedMentions() {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(3983743L);
        AllowedMentions allowedMentions = DiscordService.getAllowedMentions(Set.of(role));

        assertTrue(allowedMentions.getAllowedRoleMentions().contains(role.getId()));
        assertTrue(allowedMentions.getMentionTypes().contains(AllowedMentionType.ROLES));
    }

    private record MockedDiscordService(DiscordConfiguration discordConfiguration, DiscordApi discordApi,
                                        DiscordService discordService) {
    }
}