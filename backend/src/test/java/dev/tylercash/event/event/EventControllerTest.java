package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventDto;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.server.ResponseStatusException;

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
        AttendanceService attendanceService = mock(AttendanceService.class);
        DiscordUserCacheService discordUserCacheService = mock(DiscordUserCacheService.class);
        OAuth2User principal = mock(OAuth2User.class);
        Member member = mock(Member.class);

        when(discordConfiguration.getGuildId()).thenReturn(GUILD_ID);
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(discordService.getMemberFromServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(member);
        when(member.getNickname()).thenReturn(DISPLAY_NAME);

        EventController controller = new EventController(
                eventService, discordService, discordConfiguration, attendanceService, discordUserCacheService);
        return new EventControllerTestContext(
                controller, eventService, discordService, principal, attendanceService, discordUserCacheService);
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
        var attendee = capturedAttendee(eventCaptor);
        assertThat(attendee.getSnowflake()).isEqualTo(DISCORD_ID);
    }

    @Test
    void createEvent_acceptedAttendeeUsesServerDisplayName() {
        EventControllerTestContext ctx = setupContext();
        EventDto eventDto = buildEventDto();
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

        ctx.controller.createEvent(eventDto, ctx.principal);

        verify(ctx.eventService).createEvent(eventCaptor.capture());
        var attendee = capturedAttendee(eventCaptor);
        assertThat(attendee.getName()).isEqualTo(DISPLAY_NAME);
    }

    @Test
    void createEvent_setsCreatorFromDiscordId() {
        EventControllerTestContext ctx = setupContext();
        EventDto eventDto = buildEventDto();
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

        ctx.controller.createEvent(eventDto, ctx.principal);

        verify(ctx.eventService).createEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getCreator()).isEqualTo(DISCORD_ID);
    }

    @Test
    void createEvent_recordsAttendanceInAttendanceTable() {
        EventControllerTestContext ctx = setupContext();
        EventDto eventDto = buildEventDto();

        ctx.controller.createEvent(eventDto, ctx.principal);

        verify(ctx.attendanceService)
                .recordAttendance(any(), eq(DISCORD_ID), eq(null), eq(AttendanceStatus.ACCEPTED), eq(null));
    }

    @Test
    void createEvent_upsertsUserInCache() {
        EventControllerTestContext ctx = setupContext();
        EventDto eventDto = buildEventDto();

        ctx.controller.createEvent(eventDto, ctx.principal);

        verify(ctx.discordUserCacheService).upsertUser(DISCORD_ID, DISPLAY_NAME);
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
        AttendanceService attendanceService = mock(AttendanceService.class);
        DiscordUserCacheService discordUserCacheService = mock(DiscordUserCacheService.class);
        OAuth2User principal = mock(OAuth2User.class);
        Member member = mock(Member.class);

        when(discordConfiguration.getGuildId()).thenReturn(GUILD_ID);
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(discordService.getMemberFromServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(member);
        when(member.getNickname()).thenReturn(null);
        when(member.getEffectiveName()).thenReturn("EffectiveName");

        EventController controller = new EventController(
                eventService, discordService, discordConfiguration, attendanceService, discordUserCacheService);
        EventDto eventDto = buildEventDto();
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

        controller.createEvent(eventDto, principal);

        verify(eventService).createEvent(eventCaptor.capture());
        var attendee = capturedAttendee(eventCaptor);
        assertThat(attendee.getName()).isEqualTo("EffectiveName");
    }

    private EventControllerTestContext setupRemoveAttendeeContext() {
        EventService eventService = mock(EventService.class);
        DiscordService discordService = mock(DiscordService.class);
        DiscordConfiguration discordConfiguration = mock(DiscordConfiguration.class);
        AttendanceService attendanceService = mock(AttendanceService.class);
        DiscordUserCacheService discordUserCacheService = mock(DiscordUserCacheService.class);
        OAuth2User principal = mock(OAuth2User.class);

        when(discordConfiguration.getGuildId()).thenReturn(GUILD_ID);
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);

        EventController controller = new EventController(
                eventService, discordService, discordConfiguration, attendanceService, discordUserCacheService);
        return new EventControllerTestContext(
                controller, eventService, discordService, principal, attendanceService, discordUserCacheService);
    }

    @Test
    void removeAttendee_adminCanRemoveAnyDiscordUser() {
        EventControllerTestContext ctx = setupRemoveAttendeeContext();
        UUID eventId = UUID.randomUUID();
        when(ctx.discordService.isUserAdminOfServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(true);

        Map<String, String> result = ctx.controller.removeAttendee(eventId, "other_user", null, ctx.principal);

        verify(ctx.eventService).removeAttendee(eventId, "other_user", null);
        assertThat(result).containsEntry("message", "Removed attendee");
    }

    @Test
    void removeAttendee_adminCanRemovePlusOne() {
        EventControllerTestContext ctx = setupRemoveAttendeeContext();
        UUID eventId = UUID.randomUUID();
        when(ctx.discordService.isUserAdminOfServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(true);

        Map<String, String> result = ctx.controller.removeAttendee(eventId, null, "[+1] Dave", ctx.principal);

        verify(ctx.eventService).removeAttendee(eventId, null, "[+1] Dave");
        assertThat(result).containsEntry("message", "Removed attendee");
    }

    @Test
    void removeAttendee_nonAdminCannotRemoveDiscordUser_returns403() {
        EventControllerTestContext ctx = setupRemoveAttendeeContext();
        UUID eventId = UUID.randomUUID();
        when(ctx.discordService.isUserAdminOfServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(false);

        assertThatThrownBy(() -> ctx.controller.removeAttendee(eventId, "other_user", null, ctx.principal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("Only admins can remove Discord-authenticated attendees");
    }

    @Test
    void removeAttendee_nonAdminCanRemoveOwnPlusOne() {
        EventControllerTestContext ctx = setupRemoveAttendeeContext();
        UUID eventId = UUID.randomUUID();
        when(ctx.discordService.isUserAdminOfServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(false);
        when(ctx.attendanceService.isOwnerOfPlusOne(eventId, "[+1] MyGuest", DISCORD_ID))
                .thenReturn(true);

        Map<String, String> result = ctx.controller.removeAttendee(eventId, null, "[+1] MyGuest", ctx.principal);

        verify(ctx.eventService).removeAttendee(eventId, null, "[+1] MyGuest");
        assertThat(result).containsEntry("message", "Removed attendee");
    }

    @Test
    void removeAttendee_nonAdminCannotRemoveOthersPlusOne_returns403() {
        EventControllerTestContext ctx = setupRemoveAttendeeContext();
        UUID eventId = UUID.randomUUID();
        when(ctx.discordService.isUserAdminOfServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(false);
        when(ctx.attendanceService.isOwnerOfPlusOne(eventId, "[+1] NotMine", DISCORD_ID))
                .thenReturn(false);

        assertThatThrownBy(() -> ctx.controller.removeAttendee(eventId, null, "[+1] NotMine", ctx.principal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("You can only remove +1 guests that you added");
    }

    private static dev.tylercash.event.event.model.Attendee capturedAttendee(ArgumentCaptor<Event> eventCaptor) {
        return eventCaptor.getValue().getAccepted().iterator().next();
    }

    private record EventControllerTestContext(
            EventController controller,
            EventService eventService,
            DiscordService discordService,
            OAuth2User principal,
            AttendanceService attendanceService,
            DiscordUserCacheService discordUserCacheService) {}
}
