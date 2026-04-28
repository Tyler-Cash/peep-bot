package dev.tylercash.event.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.tylercash.event.security.dev.DevUserProperties;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DiscordAuthServiceTest {
    private static final long GUILD_ID = 123L;
    private static final long USER_ID = 456L;

    private JDA jda;
    private Guild guild;
    private DiscordConfiguration config;

    @BeforeEach
    void setUp() {
        jda = mock(JDA.class);
        guild = mock(Guild.class);
        config = new DiscordConfiguration();
        config.setAdminRole("event-admin");
        when(jda.getGuildById(GUILD_ID)).thenReturn(guild);
    }

    private DiscordAuthService service(Optional<DevUserProperties> devUser) {
        return new DiscordAuthService(jda, config, devUser);
    }

    @SuppressWarnings("unchecked")
    private void stubMemberLookup(Member member) {
        CacheRestAction<Member> action = mock(CacheRestAction.class);
        when(guild.retrieveMemberById(USER_ID)).thenReturn(action);
        when(action.complete()).thenReturn(member);
    }

    private Role roleNamed(String name) {
        Role role = mock(Role.class);
        when(role.getName()).thenReturn(name);
        return role;
    }

    @Nested
    @DisplayName("isMember")
    class IsMember {
        @Test
        @DisplayName("returns true when member lookup succeeds")
        void returnsTrueWhenMemberExists() {
            stubMemberLookup(mock(Member.class));
            assertThat(service(Optional.empty()).isMember(GUILD_ID, USER_ID)).isTrue();
        }

        @Test
        @DisplayName("returns false when member lookup returns null")
        void returnsFalseWhenMemberMissing() {
            stubMemberLookup(null);
            assertThat(service(Optional.empty()).isMember(GUILD_ID, USER_ID)).isFalse();
        }
    }

    @Nested
    @DisplayName("hasRole")
    class HasRole {
        @Test
        @DisplayName("returns true when member has a role matching case-insensitively")
        void matchesRoleNameIgnoringCase() {
            Member member = mock(Member.class);
            List<Role> roles = List.of(roleNamed("Event-Admin"));
            when(member.getRoles()).thenReturn(roles);
            stubMemberLookup(member);

            assertThat(service(Optional.empty()).hasRole(GUILD_ID, USER_ID, "event-admin"))
                    .isTrue();
        }

        @Test
        @DisplayName("returns false when member has no matching role")
        void returnsFalseWhenRoleMissing() {
            Member member = mock(Member.class);
            List<Role> roles = List.of(roleNamed("some-other-role"));
            when(member.getRoles()).thenReturn(roles);
            stubMemberLookup(member);

            assertThat(service(Optional.empty()).hasRole(GUILD_ID, USER_ID, "event-admin"))
                    .isFalse();
        }

        @Test
        @DisplayName("returns false when member is not in the guild")
        void returnsFalseWhenMemberNotFound() {
            stubMemberLookup(null);
            assertThat(service(Optional.empty()).hasRole(GUILD_ID, USER_ID, "event-admin"))
                    .isFalse();
        }

        @Test
        @DisplayName("returns true without touching JDA when dev force-admin is enabled")
        void devForceAdminShortCircuits() {
            DevUserProperties dev = new DevUserProperties();
            dev.setForceAdmin(true);

            assertThat(service(Optional.of(dev)).hasRole(GUILD_ID, USER_ID, "anything"))
                    .isTrue();
            // guild lookup is lazily triggered by getMember; force-admin must skip it.
            org.mockito.Mockito.verifyNoInteractions(guild);
        }

        @Test
        @DisplayName("does not short-circuit when dev properties are present but force-admin is off")
        void devPropertiesWithoutForceAdmin() {
            DevUserProperties dev = new DevUserProperties();
            dev.setForceAdmin(false);
            Member member = mock(Member.class);
            List<Role> roles = List.of(roleNamed("event-admin"));
            when(member.getRoles()).thenReturn(roles);
            stubMemberLookup(member);

            assertThat(service(Optional.of(dev)).hasRole(GUILD_ID, USER_ID, "event-admin"))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("isEventAdmin")
    class IsEventAdmin {
        @Test
        @DisplayName("delegates to hasRole with the configured admin role name")
        void usesConfiguredAdminRole() {
            config.setAdminRole("custom-admin");
            Member member = mock(Member.class);
            List<Role> roles = List.of(roleNamed("custom-admin"));
            when(member.getRoles()).thenReturn(roles);
            stubMemberLookup(member);

            assertThat(service(Optional.empty()).isEventAdmin(GUILD_ID, USER_ID))
                    .isTrue();
        }

        @Test
        @DisplayName("returns false when member lacks the admin role")
        void returnsFalseWithoutAdminRole() {
            Member member = mock(Member.class);
            List<Role> roles = List.of(roleNamed("regular-user"));
            when(member.getRoles()).thenReturn(roles);
            stubMemberLookup(member);

            assertThat(service(Optional.empty()).isEventAdmin(GUILD_ID, USER_ID))
                    .isFalse();
        }
    }
}
