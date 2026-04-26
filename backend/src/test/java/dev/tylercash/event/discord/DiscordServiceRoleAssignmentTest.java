package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.AttendanceStatus;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.global.FeatureTogglesConfiguration;
import io.github.resilience4j.ratelimiter.RateLimiter;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiscordServiceRoleAssignmentTest {
    private static final long GUILD_ID = 42L;
    private static final String SNOWFLAKE = "123456789";

    private DiscordConfiguration config;
    private JDA jda;
    private Guild guild;
    private DiscordRoleService roleService;

    private DiscordService service;

    @BeforeEach
    void setUp() {
        config = new DiscordConfiguration();
        config.setGuildId(GUILD_ID);
        jda = mock(JDA.class);
        guild = mock(Guild.class);
        when(jda.getGuildById(GUILD_ID)).thenReturn(guild);
        roleService = mock(DiscordRoleService.class);

        Clock clock = Clock.fixed(ZonedDateTime.parse("2026-05-01T12:00:00Z").toInstant(), ZoneId.of("UTC"));
        service = new DiscordService(
                config,
                mock(EmbedService.class),
                mock(EventRepository.class),
                mock(FeatureTogglesConfiguration.class),
                mock(RateLimiter.class),
                clock,
                jda,
                mock(DiscordChannelService.class),
                mock(DiscordMessageService.class),
                roleService,
                mock(DiscordAuthService.class));
    }

    private Event event(String name) {
        Event e = new Event();
        e.setName(name);
        e.setCreator("tester");
        e.setDateTime(ZonedDateTime.parse("2026-06-01T10:00:00Z"));
        e.setServerId(GUILD_ID);
        return e;
    }

    private Role roleWithId(long id) {
        Role r = mock(Role.class);
        when(r.getIdLong()).thenReturn(id);
        return r;
    }

    @SuppressWarnings("unchecked")
    private void stubMemberLookup(Member member) {
        CacheRestAction<Member> action = mock(CacheRestAction.class);
        when(guild.retrieveMemberById(SNOWFLAKE)).thenReturn(action);
        when(action.complete()).thenReturn(member);
    }

    @Test
    @DisplayName("createEventRoles creates three roles and records their ids on the event")
    void createEventRoles_createsAllThreeRoles() {
        Event event = event("Brunch");
        Role accepted = roleWithId(1L);
        Role declined = roleWithId(2L);
        Role maybe = roleWithId(3L);
        when(roleService.createRole(eq(guild), eq("Brunch - Accepted"))).thenReturn(accepted);
        when(roleService.createRole(eq(guild), eq("Brunch - Declined"))).thenReturn(declined);
        when(roleService.createRole(eq(guild), eq("Brunch - Maybe"))).thenReturn(maybe);

        service.createEventRoles(event);

        assertThat(event.getAcceptedRoleId()).isEqualTo(1L);
        assertThat(event.getDeclinedRoleId()).isEqualTo(2L);
        assertThat(event.getMaybeRoleId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("createEventRoles truncates the base name at 89 characters before appending the suffix")
    void createEventRoles_truncatesLongNamesAt89Chars() {
        String longName = "a".repeat(200);
        String expectedBase = "a".repeat(89);
        Event event = event(longName);
        Role role = roleWithId(1L);
        when(roleService.createRole(eq(guild), any())).thenReturn(role);

        service.createEventRoles(event);

        verify(roleService).createRole(guild, expectedBase + " - Accepted");
        verify(roleService).createRole(guild, expectedBase + " - Declined");
        verify(roleService).createRole(guild, expectedBase + " - Maybe");
    }

    @Test
    @DisplayName("createEventRoles is idempotent — roles with existing ids are not recreated")
    void createEventRoles_skipsRolesThatAlreadyExist() {
        Event event = event("Brunch");
        event.setAcceptedRoleId(100L);
        event.setDeclinedRoleId(200L);
        Role maybe = roleWithId(300L);
        when(roleService.createRole(eq(guild), eq("Brunch - Maybe"))).thenReturn(maybe);

        service.createEventRoles(event);

        verify(roleService, never()).createRole(guild, "Brunch - Accepted");
        verify(roleService, never()).createRole(guild, "Brunch - Declined");
        verify(roleService).createRole(guild, "Brunch - Maybe");
        assertThat(event.getAcceptedRoleId()).isEqualTo(100L);
        assertThat(event.getDeclinedRoleId()).isEqualTo(200L);
        assertThat(event.getMaybeRoleId()).isEqualTo(300L);
    }

    @Test
    @DisplayName("deleteEventRoles delegates deletion of all three role ids (including nulls)")
    void deleteEventRoles_delegatesAllThree() {
        Event event = event("Brunch");
        event.setAcceptedRoleId(1L);
        event.setDeclinedRoleId(2L);
        // Maybe role id left null to verify null is forwarded to the role service

        service.deleteEventRoles(event);

        verify(roleService).deleteRole(guild, 1L);
        verify(roleService).deleteRole(guild, 2L);
        verify(roleService).deleteRole(guild, (Long) null);
    }

    @Test
    @DisplayName("assignEventRole removes existing RSVP roles then adds the ACCEPTED role")
    void assignEventRole_accepted_removesOthersThenAdds() {
        Event event = event("Brunch");
        event.setAcceptedRoleId(1L);
        event.setDeclinedRoleId(2L);
        event.setMaybeRoleId(3L);
        Member member = mock(Member.class);
        stubMemberLookup(member);

        service.assignEventRole(event, SNOWFLAKE, AttendanceStatus.ACCEPTED);

        verify(roleService).removeRoleFromMember(guild, member, 1L);
        verify(roleService).removeRoleFromMember(guild, member, 2L);
        verify(roleService).removeRoleFromMember(guild, member, 3L);
        verify(roleService).addRoleToMember(guild, member, 1L);
    }

    @Test
    @DisplayName("assignEventRole adds the DECLINED role when status is DECLINED")
    void assignEventRole_declined() {
        Event event = event("Brunch");
        event.setAcceptedRoleId(1L);
        event.setDeclinedRoleId(2L);
        event.setMaybeRoleId(3L);
        Member member = mock(Member.class);
        stubMemberLookup(member);

        service.assignEventRole(event, SNOWFLAKE, AttendanceStatus.DECLINED);

        verify(roleService).addRoleToMember(guild, member, 2L);
    }

    @Test
    @DisplayName("assignEventRole adds the MAYBE role when status is MAYBE")
    void assignEventRole_maybe() {
        Event event = event("Brunch");
        event.setAcceptedRoleId(1L);
        event.setDeclinedRoleId(2L);
        event.setMaybeRoleId(3L);
        Member member = mock(Member.class);
        stubMemberLookup(member);

        service.assignEventRole(event, SNOWFLAKE, AttendanceStatus.MAYBE);

        verify(roleService).addRoleToMember(guild, member, 3L);
    }

    @Test
    @DisplayName("assignEventRole passes a null role id when status is REMOVED — role service handles the no-op")
    void assignEventRole_removedPassesNullRoleId() {
        Event event = event("Brunch");
        event.setAcceptedRoleId(1L);
        event.setDeclinedRoleId(2L);
        event.setMaybeRoleId(3L);
        Member member = mock(Member.class);
        stubMemberLookup(member);

        service.assignEventRole(event, SNOWFLAKE, AttendanceStatus.REMOVED);

        verify(roleService).addRoleToMember(guild, member, (Long) null);
    }

    @Test
    @DisplayName("assignEventRole short-circuits when the member is not in the guild")
    void assignEventRole_nullMemberShortCircuits() {
        Event event = event("Brunch");
        event.setAcceptedRoleId(1L);
        stubMemberLookup(null);

        service.assignEventRole(event, SNOWFLAKE, AttendanceStatus.ACCEPTED);

        verifyNoInteractions(roleService);
    }

    @Test
    @DisplayName("removeAllEventRoles removes each of the three RSVP roles from the member")
    void removeAllEventRoles_removesAllThree() {
        Event event = event("Brunch");
        event.setAcceptedRoleId(1L);
        event.setDeclinedRoleId(2L);
        event.setMaybeRoleId(3L);
        Member member = mock(Member.class);
        stubMemberLookup(member);

        service.removeAllEventRoles(event, SNOWFLAKE);

        verify(roleService).removeRoleFromMember(guild, member, 1L);
        verify(roleService).removeRoleFromMember(guild, member, 2L);
        verify(roleService).removeRoleFromMember(guild, member, 3L);
    }

    @Test
    @DisplayName("removeAllEventRoles is a no-op when the member is not in the guild")
    void removeAllEventRoles_noopWhenMemberMissing() {
        Event event = event("Brunch");
        event.setAcceptedRoleId(1L);
        stubMemberLookup(null);

        service.removeAllEventRoles(event, SNOWFLAKE);

        verifyNoInteractions(roleService);
    }
}
