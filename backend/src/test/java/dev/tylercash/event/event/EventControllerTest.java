package dev.tylercash.event.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.discord.GuildMembershipService;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.AttendanceSummary;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.event.model.EventDetailDto;
import dev.tylercash.event.event.model.EventDto;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
        AttendanceService attendanceService = mock(AttendanceService.class);
        DiscordUserCacheService discordUserCacheService = mock(DiscordUserCacheService.class);
        GuildMembershipService guildMembershipService = mock(GuildMembershipService.class);
        OAuth2User principal = mock(OAuth2User.class);
        Member member = mock(Member.class);

        net.dv8tion.jda.api.entities.User jdaUser = mock(net.dv8tion.jda.api.entities.User.class);
        when(jdaUser.getName()).thenReturn(USERNAME);

        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(discordService.getMemberFromServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(member);
        when(member.getNickname()).thenReturn(DISPLAY_NAME);
        when(member.getUser()).thenReturn(jdaUser);

        EventController controller = new EventController(
                eventService, discordService, attendanceService, discordUserCacheService, guildMembershipService);
        return new EventControllerTestContext(
                controller,
                eventService,
                discordService,
                principal,
                member,
                attendanceService,
                discordUserCacheService,
                guildMembershipService);
    }

    private EventDto buildEventDto() {
        EventDto dto = new EventDto();
        dto.setName("Test Event");
        dto.setDescription("A test event");
        dto.setDateTime(ZonedDateTime.now().plusDays(1));
        dto.setCapacity(0);
        dto.setCost(0);
        dto.setLocation("");
        dto.setGuildId(GUILD_ID);
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

        verify(ctx.discordUserCacheService).upsertUser(DISCORD_ID, DISPLAY_NAME, USERNAME, null, GUILD_ID);
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
        AttendanceService attendanceService = mock(AttendanceService.class);
        DiscordUserCacheService discordUserCacheService = mock(DiscordUserCacheService.class);
        GuildMembershipService guildMembershipService = mock(GuildMembershipService.class);
        OAuth2User principal = mock(OAuth2User.class);
        Member member = mock(Member.class);

        net.dv8tion.jda.api.entities.User jdaUser = mock(net.dv8tion.jda.api.entities.User.class);
        when(jdaUser.getName()).thenReturn(USERNAME);

        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);
        when(discordService.getMemberFromServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(member);
        when(member.getNickname()).thenReturn(null);
        when(member.getEffectiveName()).thenReturn("EffectiveName");
        when(member.getUser()).thenReturn(jdaUser);

        EventController controller = new EventController(
                eventService, discordService, attendanceService, discordUserCacheService, guildMembershipService);
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
        AttendanceService attendanceService = mock(AttendanceService.class);
        DiscordUserCacheService discordUserCacheService = mock(DiscordUserCacheService.class);
        GuildMembershipService guildMembershipService = mock(GuildMembershipService.class);
        OAuth2User principal = mock(OAuth2User.class);

        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);

        EventController controller = new EventController(
                eventService, discordService, attendanceService, discordUserCacheService, guildMembershipService);
        return new EventControllerTestContext(
                controller,
                eventService,
                discordService,
                principal,
                null,
                attendanceService,
                discordUserCacheService,
                guildMembershipService);
    }

    private Event buildEventInGuild(UUID id) {
        Event event = new Event();
        event.setId(id);
        event.setServerId(GUILD_ID);
        return event;
    }

    @Test
    void removeAttendee_adminCanRemoveAnyDiscordUser() {
        EventControllerTestContext ctx = setupRemoveAttendeeContext();
        UUID eventId = UUID.randomUUID();
        when(ctx.eventService.getEvent(eventId)).thenReturn(buildEventInGuild(eventId));
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
        when(ctx.eventService.getEvent(eventId)).thenReturn(buildEventInGuild(eventId));
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
        when(ctx.eventService.getEvent(eventId)).thenReturn(buildEventInGuild(eventId));
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
        when(ctx.eventService.getEvent(eventId)).thenReturn(buildEventInGuild(eventId));
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
        when(ctx.eventService.getEvent(eventId)).thenReturn(buildEventInGuild(eventId));
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

    private EventControllerTestContext setupGetEventContext() {
        EventService eventService = mock(EventService.class);
        DiscordUserCacheService discordUserCacheService = mock(DiscordUserCacheService.class);
        DiscordService discordService = mock(DiscordService.class);
        AttendanceService attendanceService = mock(AttendanceService.class);
        GuildMembershipService guildMembershipService = mock(GuildMembershipService.class);
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);

        EventController controller = new EventController(
                eventService, discordService, attendanceService, discordUserCacheService, guildMembershipService);
        return new EventControllerTestContext(
                controller,
                eventService,
                discordService,
                principal,
                null,
                attendanceService,
                discordUserCacheService,
                guildMembershipService);
    }

    private Event buildFullEvent(UUID id, String creator) {
        Event event = new Event();
        event.setId(id);
        event.setName("Detail Event");
        event.setDescription("Detail description");
        event.setDateTime(ZonedDateTime.now().plusDays(1));
        event.setCapacity(10);
        event.setCost(0);
        event.setLocation("Location");
        event.setCreator(creator);
        event.setServerId(GUILD_ID);
        return event;
    }

    @Test
    void getEvent_creatorSnowflakeIncludedInLookupSet() {
        EventControllerTestContext ctx = setupGetEventContext();
        UUID eventId = UUID.randomUUID();
        Event event = buildFullEvent(eventId, DISCORD_ID);
        AttendanceSummary summary = new AttendanceSummary(List.of(), List.of(), List.of());
        when(ctx.eventService.getEvent(eventId)).thenReturn(event);
        when(ctx.eventService.isCompleted(event)).thenReturn(false);
        when(ctx.attendanceService.getCurrentAttendance(eventId)).thenReturn(summary);
        when(ctx.discordUserCacheService.getDisplayNames(anyLong(), any())).thenReturn(Map.of(DISCORD_ID, "Host Name"));
        when(ctx.discordUserCacheService.getUsernames(any())).thenReturn(Map.of(DISCORD_ID, "host_user"));

        ctx.controller.getEvent(eventId, ctx.principal);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> snowflakesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(ctx.discordUserCacheService).getDisplayNames(anyLong(), snowflakesCaptor.capture());
        assertThat(snowflakesCaptor.getValue()).contains(DISCORD_ID);
    }

    @Test
    void getEvent_hostAndAvatarUrlPopulatedInReturnedDto() {
        EventControllerTestContext ctx = setupGetEventContext();
        UUID eventId = UUID.randomUUID();
        Event event = buildFullEvent(eventId, DISCORD_ID);
        AttendanceSummary summary = new AttendanceSummary(List.of(), List.of(), List.of());

        when(ctx.eventService.getEvent(eventId)).thenReturn(event);
        when(ctx.eventService.isCompleted(event)).thenReturn(false);
        when(ctx.attendanceService.getCurrentAttendance(eventId)).thenReturn(summary);
        when(ctx.discordUserCacheService.getDisplayNames(anyLong(), any())).thenReturn(Map.of(DISCORD_ID, "Host Name"));
        when(ctx.discordUserCacheService.getUsernames(any())).thenReturn(Map.of(DISCORD_ID, "host_user"));

        EventDetailDto result = ctx.controller.getEvent(eventId, ctx.principal);

        assertThat(result.getHost()).isEqualTo("Host Name");
        assertThat(result.getHostAvatarUrl()).isEqualTo("/api/avatar/" + DISCORD_ID);
    }

    private static dev.tylercash.event.event.model.Attendee capturedAttendee(ArgumentCaptor<Event> eventCaptor) {
        return eventCaptor.getValue().getAccepted().iterator().next();
    }

    private EventControllerTestContext setupGetEventsContext() {
        EventService eventService = mock(EventService.class);
        DiscordUserCacheService discordUserCacheService = mock(DiscordUserCacheService.class);
        DiscordService discordService = mock(DiscordService.class);
        AttendanceService attendanceService = mock(AttendanceService.class);
        GuildMembershipService guildMembershipService = mock(GuildMembershipService.class);
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("id")).thenReturn(DISCORD_ID);

        EventController controller = new EventController(
                eventService, discordService, attendanceService, discordUserCacheService, guildMembershipService);
        return new EventControllerTestContext(
                controller,
                eventService,
                discordService,
                principal,
                null,
                attendanceService,
                discordUserCacheService,
                guildMembershipService);
    }

    @Test
    void getEvents_populatesHostFromCacheDisplayName() {
        EventControllerTestContext ctx = setupGetEventsContext();
        Event event = new Event();
        event.setId(UUID.randomUUID());
        event.setName("Test Event");
        event.setDescription("A test event");
        event.setDateTime(ZonedDateTime.now().plusDays(1));
        event.setCapacity(10);
        event.setCost(0);
        event.setLocation("Test Location");
        event.setCreator(DISCORD_ID);

        Page<Event> eventPage = new PageImpl<>(List.of(event), PageRequest.of(0, 10), 1);
        when(ctx.eventService.getActiveEvents(any(), eq(GUILD_ID))).thenReturn(eventPage);
        when(ctx.discordUserCacheService.getDisplayNames(eq(GUILD_ID), eq(Set.of(DISCORD_ID))))
                .thenReturn(Map.of(DISCORD_ID, "Resolved Name"));
        when(ctx.discordUserCacheService.getUsernames(Set.of(DISCORD_ID))).thenReturn(Map.of(DISCORD_ID, "username"));

        Page<EventDto> result = ctx.controller.getEvents(GUILD_ID, PageRequest.of(0, 10), ctx.principal);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getHost()).isEqualTo("Resolved Name");
        assertThat(result.getContent().get(0).getHostUsername()).isEqualTo("username");
        assertThat(result.getContent().get(0).getHostAvatarUrl()).isEqualTo("/api/avatar/" + DISCORD_ID);
    }

    @Test
    void getEvents_fallsBackToSnowflakeWhenNotInCache() {
        EventControllerTestContext ctx = setupGetEventsContext();
        Event event = new Event();
        event.setId(UUID.randomUUID());
        event.setName("Test Event");
        event.setDescription("A test event");
        event.setDateTime(ZonedDateTime.now().plusDays(1));
        event.setCapacity(10);
        event.setCost(0);
        event.setLocation("Test Location");
        event.setCreator(DISCORD_ID);

        Page<Event> eventPage = new PageImpl<>(List.of(event), PageRequest.of(0, 10), 1);
        when(ctx.eventService.getActiveEvents(any(), eq(GUILD_ID))).thenReturn(eventPage);
        when(ctx.discordUserCacheService.getDisplayNames(eq(GUILD_ID), eq(Set.of(DISCORD_ID))))
                .thenReturn(Map.of());
        when(ctx.discordUserCacheService.getUsernames(Set.of(DISCORD_ID))).thenReturn(Map.of());

        Page<EventDto> result = ctx.controller.getEvents(GUILD_ID, PageRequest.of(0, 10), ctx.principal);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getHost()).isEqualTo(DISCORD_ID);
        assertThat(result.getContent().get(0).getHostAvatarUrl()).isEqualTo("/api/avatar/" + DISCORD_ID);
    }

    @Test
    void recategorizeEvent_adminUser_callsServiceAndReturnsMessage() {
        EventControllerTestContext ctx = setupGetEventsContext();
        UUID id = UUID.randomUUID();
        Event event = new Event(0L, GUILD_ID, 0L, "Test Event", DISCORD_ID, ZonedDateTime.now(), "desc");
        event.setId(id);

        when(ctx.eventService.getEvent(id)).thenReturn(event);
        when(ctx.discordService.isUserAdminOfServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(true);

        Map<String, String> result = ctx.controller.recategorizeEvent(id, ctx.principal);

        assertThat(result.get("message")).isEqualTo("Recategorization triggered");
        verify(ctx.eventService).recategorizeEvent(id);
    }

    @Test
    void recategorizeEvent_nonAdmin_throwsForbidden() {
        EventControllerTestContext ctx = setupGetEventsContext();
        UUID id = UUID.randomUUID();
        Event event = new Event(0L, GUILD_ID, 0L, "Test Event", DISCORD_ID, ZonedDateTime.now(), "desc");
        event.setId(id);

        when(ctx.eventService.getEvent(id)).thenReturn(event);
        when(ctx.discordService.isUserAdminOfServer(GUILD_ID, Long.parseLong(DISCORD_ID)))
                .thenReturn(false);

        assertThatThrownBy(() -> ctx.controller.recategorizeEvent(id, ctx.principal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(
                                ((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(403));
    }

    private record EventControllerTestContext(
            EventController controller,
            EventService eventService,
            DiscordService discordService,
            OAuth2User principal,
            Member member,
            AttendanceService attendanceService,
            DiscordUserCacheService discordUserCacheService,
            GuildMembershipService guildMembershipService) {}
}
