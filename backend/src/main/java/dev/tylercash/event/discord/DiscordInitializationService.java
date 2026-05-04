package dev.tylercash.event.discord;

import static dev.tylercash.event.discord.DiscordConfiguration.EVENT_ARCHIVE_CATEGORY;
import static dev.tylercash.event.discord.DiscordConfiguration.EVENT_CATEGORY;

import dev.tylercash.event.contract.ContractConfiguration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final GuildRepository guildRepository;

    public DiscordInitializationService(
            JDA jda,
            DiscordChannelService discordChannelService,
            ContractConfiguration contractConfig,
            DiscordUserCacheService discordUserCacheService,
            ContractGuildResolver contractGuildResolver,
            @Lazy GuildRegistrationService guildRegistrationService,
            GuildRepository guildRepository) {
        this.jda = jda;
        this.discordChannelService = discordChannelService;
        this.contractConfig = contractConfig;
        this.discordUserCacheService = discordUserCacheService;
        this.contractGuildResolver = contractGuildResolver;
        this.guildRegistrationService = guildRegistrationService;
        this.guildRepository = guildRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        List<net.dv8tion.jda.api.entities.Guild> liveGuilds = jda.getGuilds();
        log.info("Onboarding {} guilds on startup", liveGuilds.size());
        liveGuilds.forEach(guildRegistrationService::onboard);

        Set<Long> liveGuildIds = liveGuilds.stream()
                .map(net.dv8tion.jda.api.entities.Guild::getIdLong)
                .collect(Collectors.toSet());
        guildRepository.findAllByActiveTrue().stream()
                .map(Guild::getGuildId)
                .filter(id -> !liveGuildIds.contains(id))
                .forEach(id -> {
                    log.info("Guild {} is active in DB but bot is no longer a member; deactivating", id);
                    guildRegistrationService.deactivate(id);
                });
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
                        Set<String> liveSnowflakes = members.stream()
                                .map(net.dv8tion.jda.api.entities.Member::getId)
                                .collect(Collectors.toSet());
                        int removed = discordUserCacheService.pruneMembersNotIn(guild.getIdLong(), liveSnowflakes);
                        log.info(
                                "Synced {} members for guild '{}' (pruned {} stale rows)",
                                members.size(),
                                guild.getName(),
                                removed);
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
