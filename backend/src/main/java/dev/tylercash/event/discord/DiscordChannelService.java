package dev.tylercash.event.discord;

import java.util.List;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@AllArgsConstructor
public class DiscordChannelService {
    private final JDA jda;

    public Category getCategoryByName(long guildId, String name) {
        List<Category> categories = jda.getGuildById(guildId).getCategoriesByName(name, true);
        if (categories.size() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Found multiple categories named \"" + name + "\"");
        }
        if (categories.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "No category found called \"" + name + "\"");
        }
        return categories.get(0);
    }

    public Category getOrCreateCategory(Guild guild, String name) {
        List<Category> existing = guild.getCategoriesByName(name, true);
        if (!existing.isEmpty()) return existing.get(0);
        return guild.createCategory(name).complete();
    }

    public TextChannel createTextChannel(Category category, String name) {
        return category.createTextChannel(name).setPosition(99).complete();
    }

    public TextChannel createPrivateTextChannel(Category category, String name, long deniedRoleId, long allowedRoleId) {
        return category.createTextChannel(name)
                .addRolePermissionOverride(deniedRoleId, 0L, Permission.VIEW_CHANNEL.getRawValue())
                .addRolePermissionOverride(
                        allowedRoleId,
                        Permission.VIEW_CHANNEL.getRawValue()
                                | Permission.MESSAGE_SEND.getRawValue()
                                | Permission.MESSAGE_HISTORY.getRawValue(),
                        0L)
                .complete();
    }

    public TextChannel getTextChannel(long channelId) {
        return jda.getChannelById(TextChannel.class, channelId);
    }

    public void deleteChannel(long channelId) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel != null) channel.delete().queue();
    }

    public void setChannelName(long channelId, String name) {
        TextChannel channel = getTextChannel(channelId);
        if (channel != null && !channel.getName().equals(name)) {
            channel.getManager().setName(name).queue();
        }
    }

    public void moveChannelToCategory(TextChannel channel, Category target) {
        channel.getManager().setParent(target).sync().queue();
    }

    public void sortChannelsByChannelName(Category category) {
        java.util.Locale locale = java.util.Locale.ENGLISH;
        java.time.format.DateTimeFormatter monthParser = new java.time.format.DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("MMM")
                .toFormatter(locale);

        List<TextChannel> channels = new java.util.ArrayList<>(category.getTextChannels());
        channels.sort(java.util.Comparator.comparing(
                channel -> dev.tylercash.event.discord.DiscordUtil.getMonthDayFromChannelName(channel, monthParser)));

        for (int i = 0; i < channels.size(); i++) {
            channels.get(i).getManager().setPosition(i).queue();
        }
    }
}
