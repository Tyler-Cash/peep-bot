package dev.tylercash.event.discord;

import static dev.tylercash.event.discord.DiscordConfiguration.EVENT_ARCHIVE_CATEGORY;
import static dev.tylercash.event.discord.DiscordConfiguration.EVENT_CATEGORY;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Log4j2
@Service
@AllArgsConstructor
public class DiscordInitializationService {
    private final JDA jda;
    private final DiscordConfiguration discordConfiguration;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeGuild() {
        Guild guild = jda.getGuildById(discordConfiguration.getGuildId());
        if (guild == null) {
            throw new IllegalStateException(
                    "Guild " + discordConfiguration.getGuildId() + " not found. Check dev.tylercash.discord.guild-id");
        }

        Category outings = ensureCategory(guild, EVENT_CATEGORY);
        ensureCategory(guild, EVENT_ARCHIVE_CATEGORY);
        ensureSeparatorChannel(outings);
    }

    Category ensureCategory(Guild guild, String categoryName) {
        List<Category> categories = guild.getCategoriesByName(categoryName, true);
        if (!categories.isEmpty()) {
            log.info("Category '{}' already exists", categoryName);
            return categories.get(0);
        }
        log.info("Creating category '{}'", categoryName);
        return guild.createCategory(categoryName).complete();
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
