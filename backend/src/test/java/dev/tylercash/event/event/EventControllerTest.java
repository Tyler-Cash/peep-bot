package dev.tylercash.event.event;

import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Attendee;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventDto;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventControllerTest {

    private static final long GUILD_ID = 123456789L;
    private static final String DISCORD_ID = "987654321";
    private static final String USERNAME = "testuser";
    private static final String DISPLAY_NAME = "Test Nickname";

    private EventControllerTestContext setupContext() {
        EventService eventService = mock(EventService.class);
        DiscordService discordService = mock(DiscordService.class);
        DiscordConfiguration discordConfiguration = mock(DiscordConfiguration.class);
        OAuth2User principal = mock(OAuth2User.class);
        Member member = mock(Member.class);

        when(discordConfiguration.getGuildId()).thenReturn(GUILD_ID);
        when(principal.getAttribute("username")).thenReturn(USERNAME);
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(discordService.getMemberFromServer(GUILD_ID, Long.parseLong(DISCORD_ID))).thenReturn(member);
        when(member.getNickname()).thenReturn(DISPLAY_NAME);

        EventController controller = new EventController(eventService, discordService, discordConfiguration);
        return new EventControllerTestContext(controller, eventService, discordService, principal);
    }

    private EventDto buildEventDto() {
        EventDto dto = new EventDto();
        dto.setName("Test Event");
        dto.setDescription("A test event");
        dto.setDateTime(ZonedDateTime.now().plusDays(1));
        dto.setCapacity(0);
        dto.setCost(0);
        dto.setLocation("");
        return dto;
    }

    @Test
    void createEvent_autoAcceptsCreator() {
        EventControllerTestContext ctx = setupContext();
        EventDto eventDto = buildEventDto();
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

        ctx.controller.createEvent(eventDto, ctx.principal);

        verify(ctx.eventService).createEvent(eventCaptor.capture());
        Event capturedEvent = eventCaptor.getValue();
        assertThat(capturedEvent.getAccepted()).hasSize(1);
    }

    @Test
    void createEvent_acceptedAttendeeHasCorrectSnowflake() {
        EventControllerTestContext ctx = setupContext();
        EventDto eventDto = buildEventDto();
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

        ctx.controller.createEvent(eventDto, ctx.principal);

        verify(ctx.eventService).createEvent(eventCaptor.capture());
        Attendee attendee = capturedAttendee(eventCaptor);
        assertThat(attendee.getSnowflake()).isEqualTo(DISCORD_ID);
    }

    @Test
    void createEvent_acceptedAttendeeUsesServerDisplayName() {
        EventControllerTestContext ctx = setupContext();
        EventDto eventDto = buildEventDto();
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

        ctx.controller.createEvent(eventDto, ctx.principal);

        verify(ctx.eventService).createEvent(eventCaptor.capture());
        Attendee attendee = capturedAttendee(eventCaptor);
        assertThat(attendee.getName()).isEqualTo(DISPLAY_NAME);
    }

    @Test
    void createEvent_setsCreatorFromUsername() {
        EventControllerTestContext ctx = setupContext();
        EventDto eventDto = buildEventDto();
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

        ctx.controller.createEvent(eventDto, ctx.principal);

        verify(ctx.eventService).createEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getCreator()).isEqualTo(USERNAME);
    }

    @Test
    void createEvent_returnsSuccessMessage() {
        EventControllerTestContext ctx = setupContext();
        EventDto eventDto = buildEventDto();

        Map<String, String> result = ctx.controller.createEvent(eventDto, ctx.principal);

        assertThat(result).containsEntry("message", "Created event for Test Event");
    }

    @Test
    void createEvent_resolvesDisplayNameFromDiscordServer() {
        EventControllerTestContext ctx = setupContext();
        EventDto eventDto = buildEventDto();

        ctx.controller.createEvent(eventDto, ctx.principal);

        verify(ctx.discordService).getMemberFromServer(GUILD_ID, Long.parseLong(DISCORD_ID));
    }

    @Test
    void createEvent_usesEffectiveNameWhenNoNickname() {
        EventService eventService = mock(EventService.class);
        DiscordService discordService = mock(DiscordService.class);
        DiscordConfiguration discordConfiguration = mock(DiscordConfiguration.class);
        OAuth2User principal = mock(OAuth2User.class);
        Member member = mock(Member.class);

        when(discordConfiguration.getGuildId()).thenReturn(GUILD_ID);
        when(principal.getAttribute("username")).thenReturn(USERNAME);
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(discordService.getMemberFromServer(GUILD_ID, Long.parseLong(DISCORD_ID))).thenReturn(member);
        when(member.getNickname()).thenReturn(null);
        when(member.getEffectiveName()).thenReturn("EffectiveName");

        EventController controller = new EventController(eventService, discordService, discordConfiguration);
        EventDto eventDto = buildEventDto();
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

        controller.createEvent(eventDto, principal);

        verify(eventService).createEvent(eventCaptor.capture());
        Attendee attendee = capturedAttendee(eventCaptor);
        assertThat(attendee.getName()).isEqualTo("EffectiveName");
    }

    private static Attendee capturedAttendee(ArgumentCaptor<Event> eventCaptor) {
        return eventCaptor.getValue().getAccepted().iterator().next();
    }

    private record EventControllerTestContext(
            EventController controller,
            EventService eventService,
            DiscordService discordService,
            OAuth2User principal) {
    }
}
