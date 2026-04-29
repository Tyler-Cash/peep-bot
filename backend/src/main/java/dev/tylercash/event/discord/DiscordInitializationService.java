package dev.tylercash.event.discord;

import static dev.tylercash.event.discord.DiscordConfiguration.EVENT_ARCHIVE_CATEGORY;
import static dev.tylercash.event.discord.DiscordConfiguration.EVENT_CATEGORY;

import dev.tylercash.event.contract.ContractConfiguration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DiscordInitializationService {
    private final JDA jda;
    private final DiscordChannelService discordChannelService;
    private final ContractConfiguration contractConfig;
    private final DiscordUserCacheService discordUserCacheService;
    private final ContractGuildResolver contractGuildResolver;
    private final GuildRegistrationService guildRegistrationService;

    public DiscordInitializationService(
            JDA jda,
            DiscordChannelService discordChannelService,
            ContractConfiguration contractConfig,
            DiscordUserCacheService discordUserCacheService,
            ContractGuildResolver contractGuildResolver,
            @Lazy GuildRegistrationService guildRegistrationService) {
        this.jda = jda;
        this.discordChannelService = discordChannelService;
        this.contractConfig = contractConfig;
        this.discordUserCacheService = discordUserCacheService;
        this.contractGuildResolver = contractGuildResolver;
        this.guildRegistrationService = guildRegistrationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Onboarding {} guilds on startup", jda.getGuilds().size());
        jda.getGuilds().forEach(guildRegistrationService::onboard);
    }

    /** Per-guild init invoked by GuildRegistrationService. Idempotent. */
    public void initialise(net.dv8tion.jda.api.entities.Guild jdaGuild, Guild row) {
        log.info("Initialising guild '{}' ({})", jdaGuild.getName(), jdaGuild.getIdLong());
        syncGuildMembers(jdaGuild);

        Category outings = ensureCategory(jdaGuild, EVENT_CATEGORY);
        ensureCategory(jdaGuild, EVENT_ARCHIVE_CATEGORY);
        if (contractGuildResolver.isContractsGuild(jdaGuild.getIdLong())) {
            ensureCategory(jdaGuild, contractConfig.getCategoryName());
        }
        ensureSeparatorChannel(outings, row.getSeparatorChannel());
    }

    private void syncGuildMembers(net.dv8tion.jda.api.entities.Guild guild) {
        log.info("Syncing members for guild '{}'...", guild.getName());
        try {
            guild.loadMembers()
                    .onSuccess(members -> {
                        members.forEach(member -> discordUserCacheService.registerIfMissing(
                                member.getId(),
                                DiscordUtil.getUserDisplayName(member),
                                member.getUser().getName(),
                                guild.getIdLong()));
                        log.info("Synced {} members for guild '{}'", members.size(), guild.getName());
                    })
                    .get();
        } catch (Exception e) {
            log.error("Failed to sync guild members", e);
        }
    }

    private Category ensureCategory(net.dv8tion.jda.api.entities.Guild guild, String categoryName) {
        return discordChannelService.getOrCreateCategory(guild, categoryName);
    }

    private void ensureSeparatorChannel(Category category, String separatorName) {
        if (separatorName == null || separatorName.isBlank()) {
            return;
        }
        List<TextChannel> channels = category.getTextChannels();
        boolean exists = channels.stream().anyMatch(ch -> ch.getName().equalsIgnoreCase(separatorName));
        if (exists) {
            log.info("Separator channel '{}' already exists in '{}'", separatorName, category.getName());
            return;
        }
        log.info("Creating separator channel '{}' in '{}'", separatorName, category.getName());
        category.createTextChannel(separatorName).setPosition(0).complete();
    }
}
