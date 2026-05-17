package dev.tylercash.event.discord;

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

    @Observed(name = "discord.role.create")
    public Role createRole(Guild guild, String name) {
        return guild.createRole().setName(name).complete();
    }

    @Observed(name = "discord.role.delete")
    public void deleteRole(Guild guild, Long roleId) {
        if (roleId == null) return;
        Role role = guild.getRoleById(roleId);
        if (role != null) role.delete().queue();
    }

    @Observed(name = "discord.role.get-by-name")
    public List<Role> getRolesByName(long guildId, String name) {
        List<Role> roles = jda.getGuildById(guildId).getRolesByName(name, true);
        if (roles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No roles found matching name " + name);
        }
        return roles;
    }

    @Observed(name = "discord.role.add-to-member")
    public void addRoleToMember(Guild guild, Member member, Long roleId) {
        if (roleId == null) return;
        Role role = guild.getRoleById(roleId);
        if (role != null) guild.addRoleToMember(member, role).queue();
    }

    @Observed(name = "discord.role.remove-from-member")
    public void removeRoleFromMember(Guild guild, Member member, Long roleId) {
        if (roleId == null) return;
        Role role = guild.getRoleById(roleId);
        if (role != null && member.getRoles().contains(role)) {
            guild.removeRoleFromMember(member, role).queue();
        }
    }
}
