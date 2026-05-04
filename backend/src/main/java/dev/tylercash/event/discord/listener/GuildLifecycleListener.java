package dev.tylercash.event.discord.listener;

import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.discord.GuildRegistrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GuildLifecycleListener extends ListenerAdapter {
    private final GuildRegistrationService guildRegistrationService;
    private final DiscordUserCacheService discordUserCacheService;

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        log.info(
                "Bot joined guild '{}' ({})",
                event.getGuild().getName(),
                event.getGuild().getIdLong());
        try {
            guildRegistrationService.onboard(event.getGuild());
        } catch (Exception e) {
            log.error("Onboarding failed for guild {}: {}", event.getGuild().getIdLong(), e.getMessage(), e);
        }
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        long guildId = event.getGuild().getIdLong();
        String snowflake = event.getUser().getId();
        log.info("Member {} removed from guild {}; evicting guild_member row", snowflake, guildId);
        try {
            discordUserCacheService.removeMember(guildId, snowflake);
        } catch (Exception e) {
            log.error("Failed to remove member {} from guild {}: {}", snowflake, guildId, e.getMessage(), e);
        }
    }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        log.info(
                "Bot left guild '{}' ({})",
                event.getGuild().getName(),
                event.getGuild().getIdLong());
        guildRegistrationService.deactivate(event.getGuild().getIdLong());
    }
}
