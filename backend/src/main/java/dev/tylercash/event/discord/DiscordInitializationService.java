package dev.tylercash.event.discord;

import static dev.tylercash.event.discord.DiscordConfiguration.EVENT_ARCHIVE_CATEGORY;
import static dev.tylercash.event.discord.DiscordConfiguration.EVENT_CATEGORY;

import dev.tylercash.event.contract.ContractConfiguration;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@AllArgsConstructor
public class DiscordInitializationService {
    private final JDA jda;
    private final DiscordConfiguration discordConfiguration;
    private final DiscordChannelService discordChannelService;
    private final ContractConfiguration contractConfig;
    private final DiscordUserCacheService discordUserCacheService;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeGuild() {
        Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
        if (guild == null) {
            throw new IllegalStateException(
                    "Guild " + discordConfiguration.getGuildId() + " not found. Check dev.tylercash.discord.guild-id");
        }

        syncGuildMembers(guild);

        Category outings = ensureCategory(guild, EVENT_CATEGORY);
        ensureCategory(guild, EVENT_ARCHIVE_CATEGORY);
        ensureCategory(guild, contractConfig.getCategoryName());
        ensureSeparatorChannel(outings);
        resolveEmojiFields(guild, discordConfiguration.getEmoji());
        resolveEmojiFields(guild, contractConfig.getEmoji());
    }

    private void syncGuildMembers(Guild guild) {
        log.info("Syncing members for guild '{}'...", guild.getName());
        try {
            guild.loadMembers()
                    .onSuccess(members -> {
                        members.forEach(member -> {
                            discordUserCacheService.registerIfMissing(
                                    member.getId(), DiscordUtil.getUserDisplayName(member), guild.getIdLong());
                        });
                        log.info("Synced {} members for guild '{}'", members.size(), guild.getName());
                    })
                    .get(); // Wait for completion
        } catch (Exception e) {
            log.error("Failed to sync guild members", e);
        }
    }

    void resolveEmojiFields(Guild guild, Object emojiConfig) {
        for (java.lang.reflect.Field field : emojiConfig.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            String name = field.getName();
            List<RichCustomEmoji> matches = guild.getEmojisByName(name, true);
            try {
                if (!matches.isEmpty()) {
                    String mention = matches.getFirst().getAsMention();
                    log.info("Resolved custom emoji '{}' → {}", name, mention);
                    field.set(emojiConfig, mention);
                } else {
                    log.info("No custom emoji named '{}' found; using default {}", name, field.get(emojiConfig));
                }
            } catch (IllegalAccessException e) {
                log.warn("Could not resolve emoji field '{}'", name, e);
            }
        }
    }

    Category ensureCategory(Guild guild, String categoryName) {
        return discordChannelService.getOrCreateCategory(guild, categoryName);
    }

    void ensureSeparatorChannel(Category category) {
        String separatorName = discordConfiguration.getSeperatorChannel();
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
