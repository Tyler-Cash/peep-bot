package dev.tylercash.event.discord.listener;

import dev.tylercash.event.discord.DiscordUserCacheService;
import dev.tylercash.event.discord.GuildRegistrationService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GuildLifecycleListener extends ListenerAdapter {
    private final GuildRegistrationService guildRegistrationService;
    private final DiscordUserCacheService discordUserCacheService;
    private final ObservationRegistry observationRegistry;
    private final Executor executor;

    public GuildLifecycleListener(
            GuildRegistrationService guildRegistrationService,
            DiscordUserCacheService discordUserCacheService,
            ObservationRegistry observationRegistry,
            @Qualifier("discordListenerExecutor") Executor executor) {
        this.guildRegistrationService = guildRegistrationService;
        this.discordUserCacheService = discordUserCacheService;
        this.observationRegistry = observationRegistry;
        this.executor = executor;
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        long guildId = event.getGuild().getIdLong();
        String guildName = event.getGuild().getName();
        log.info("Bot joined guild '{}' ({})", guildName, guildId);
        net.dv8tion.jda.api.entities.Guild jdaGuild = event.getGuild();
        offload("discord.guild-join", () -> {
            try {
                guildRegistrationService.onboard(jdaGuild);
            } catch (Exception e) {
                log.error("Onboarding failed for guild {}: {}", guildId, e.getMessage(), e);
            }
        });
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        long guildId = event.getGuild().getIdLong();
        String snowflake = event.getUser().getId();
        log.info("Member {} removed from guild {}; evicting guild_member row", snowflake, guildId);
        offload("discord.guild-member-remove", () -> {
            try {
                discordUserCacheService.removeMember(guildId, snowflake);
            } catch (Exception e) {
                log.error("Failed to remove member {} from guild {}: {}", snowflake, guildId, e.getMessage(), e);
            }
        });
    }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        long guildId = event.getGuild().getIdLong();
        String guildName = event.getGuild().getName();
        log.info("Bot left guild '{}' ({})", guildName, guildId);
        offload("discord.guild-leave", () -> guildRegistrationService.deactivate(guildId));
    }

    /**
     * Submit {@code work} to the discord listener executor under a single observation that
     * outlives the JDA WS thread and only stops when the offloaded task finishes. Per-call
     * HTTP spans created inside the work (e.g. role/channel writes during onboard) then
     * chain under this root in the trace waterfall.
     */
    private void offload(String spanName, Runnable work) {
        Observation parent =
                Observation.createNotStarted(spanName, observationRegistry).start();
        try (Observation.Scope ignored = parent.openScope()) {
            executor.execute(() -> {
                try (Observation.Scope inner = parent.openScope()) {
                    work.run();
                } catch (RuntimeException e) {
                    parent.error(e);
                    throw e;
                } finally {
                    parent.stop();
                }
            });
        }
    }
}
