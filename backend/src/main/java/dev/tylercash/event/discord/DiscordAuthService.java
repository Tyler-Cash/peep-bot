package dev.tylercash.event.discord;

import dev.tylercash.event.security.dev.DevUserProperties;
import java.util.Optional;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class DiscordAuthService {
    private final JDA jda;
    private final DiscordConfiguration discordConfiguration;
    private final Optional<DevUserProperties> devUserProperties;

    public Member getMember(long guildId, long userId) {
        try {
            return jda.getGuildById(guildId).retrieveMemberById(userId).complete();
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() == ErrorResponse.UNKNOWN_USER
                    || e.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER) {
                return null;
            }
            throw e;
        }
    }

    public boolean isMember(long guildId, long userId) {
        if (devUserProperties.isPresent() && devUserProperties.get().isForceAdmin()) {
            return true;
        }
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
