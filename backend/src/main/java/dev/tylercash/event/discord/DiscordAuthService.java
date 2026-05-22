package dev.tylercash.event.discord;

import dev.tylercash.event.security.dev.DevUserProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.observation.annotation.Observed;
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
    private final GuildRepository guildRepository;
    private final Optional<DevUserProperties> devUserProperties;

    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.api.retrieve-member")
    public Member getMember(long guildId, long userId) {
        net.dv8tion.jda.api.entities.Guild jdaGuild = jda.getGuildById(guildId);
        if (jdaGuild == null) {
            return null;
        }
        try {
            return jdaGuild.retrieveMemberById(userId).complete();
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() == ErrorResponse.UNKNOWN_USER
                    || e.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Bulk-resolves members in a single Gateway request. Members not in the guild (left, unknown)
     * are simply absent from the returned list. Returns an empty list if the bot isn't in the
     * guild.
     */
    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.api.retrieve-members")
    public java.util.List<Member> getMembers(long guildId, long[] userIds) {
        if (userIds == null || userIds.length == 0) {
            return java.util.List.of();
        }
        net.dv8tion.jda.api.entities.Guild jdaGuild = jda.getGuildById(guildId);
        if (jdaGuild == null) {
            return java.util.List.of();
        }
        return jdaGuild.retrieveMembersByIds(userIds).get();
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

    public boolean isEventOrganiser(long guildId, long userId) {
        String organiserRole =
                guildRepository.findById(guildId).map(Guild::getOrganiserRole).orElse("event-organiser");
        return hasRole(guildId, userId, organiserRole);
    }

    public boolean isGuildOwner(long guildId, long userId) {
        if (devUserProperties.isPresent() && devUserProperties.get().isForceAdmin()) {
            return true;
        }
        net.dv8tion.jda.api.entities.Guild jdaGuild = jda.getGuildById(guildId);
        if (jdaGuild == null) return false;
        return jdaGuild.getOwnerIdLong() == userId;
    }
}
