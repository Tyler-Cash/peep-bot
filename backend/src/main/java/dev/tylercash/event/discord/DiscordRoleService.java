package dev.tylercash.event.discord;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import java.util.List;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@AllArgsConstructor
public class DiscordRoleService {
    private final JDA jda;
    private final ObservationRegistry observationRegistry;

    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.role.create")
    public Role createRole(Guild guild, String name) {
        tagCurrent("discord.role.name", name);
        return guild.createRole().setName(name).complete();
    }

    @Observed(name = "discord.role.delete")
    public void deleteRole(Guild guild, Long roleId) {
        if (roleId == null) {
            tagOutcome("skipped-null-id");
            return;
        }
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            tagCurrent("discord.role.id", Long.toString(roleId));
            tagOutcome("skipped-not-found");
            return;
        }
        tagRole(role);
        tagOutcome("enqueued");
        JdaObservations.queue(role.delete(), "discord.role.delete.queue", observationRegistry);
    }

    @Observed(name = "discord.role.get-by-name")
    public List<Role> getRolesByName(long guildId, String name) {
        tagCurrent("discord.role.name", name);
        List<Role> roles = jda.getGuildById(guildId).getRolesByName(name, true);
        if (roles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No roles found matching name " + name);
        }
        return roles;
    }

    @Observed(name = "discord.role.add-to-member")
    public void addRoleToMember(Guild guild, Member member, Long roleId) {
        if (roleId == null) {
            tagOutcome("skipped-null-id");
            return;
        }
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            tagCurrent("discord.role.id", Long.toString(roleId));
            tagOutcome("skipped-not-found");
            return;
        }
        tagRole(role);
        tagOutcome("enqueued");
        JdaObservations.queue(
                guild.addRoleToMember(member, role), "discord.role.add-to-member.queue", observationRegistry);
    }

    @Observed(name = "discord.role.remove-from-member")
    public void removeRoleFromMember(Guild guild, Member member, Long roleId) {
        if (roleId == null) {
            tagOutcome("skipped-null-id");
            return;
        }
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            tagCurrent("discord.role.id", Long.toString(roleId));
            tagOutcome("skipped-not-found");
            return;
        }
        tagRole(role);
        if (!member.getRoles().contains(role)) {
            tagOutcome("noop-not-assigned");
            return;
        }
        tagOutcome("enqueued");
        JdaObservations.queue(
                guild.removeRoleFromMember(member, role), "discord.role.remove-from-member.queue", observationRegistry);
    }

    private void tagRole(Role role) {
        tagCurrent("discord.role.id", role.getId());
        tagCurrent("discord.role.name", role.getName());
    }

    private void tagOutcome(String outcome) {
        tagCurrent("outcome", outcome);
    }

    /**
     * Add a low-cardinality attribute to the {@code @Observed}-created span on the current
     * thread. Silently does nothing when there is no current observation (covers tests that
     * call these methods directly without an outer scope). Wrapped here because Spring's
     * observation registry returns a Noop observation rather than null in that case, and we
     * don't want to litter the call sites with the {@code if (obs != null && !obs.isNoop())}
     * dance.
     */
    private void tagCurrent(String key, String value) {
        var obs = observationRegistry.getCurrentObservation();
        if (obs != null) obs.lowCardinalityKeyValue(key, value);
    }
}
