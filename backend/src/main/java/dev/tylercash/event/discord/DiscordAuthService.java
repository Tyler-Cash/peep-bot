package dev.tylercash.event.discord;

import dev.tylercash.event.security.dev.DevUserProperties;
import java.util.Optional;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class DiscordAuthService {
    private final JDA jda;
    private final DiscordConfiguration discordConfiguration;
    private final Optional<DevUserProperties> devUserProperties;

    public Member getMember(long guildId, long userId) {
        return jda.getGuildById(guildId).retrieveMemberById(userId).complete();
    }

    public boolean isMember(long guildId, long userId) {
        return getMember(guildId, userId) != null;
    }

    public boolean hasRole(long guildId, long userId, String roleName) {
        if (devUserProperties.isPresent() && devUserProperties.get().isForceAdmin()) {
            return true;
        }
        Member member = getMember(guildId, userId);
        return member != null
                && member.getRoles().stream().anyMatch(role -> role.getName().equalsIgnoreCase(roleName));
    }

    public boolean isEventAdmin(long guildId, long userId) {
        return hasRole(guildId, userId, discordConfiguration.getAdminRole());
    }
}
