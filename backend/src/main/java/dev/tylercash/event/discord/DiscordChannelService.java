package dev.tylercash.event.discord;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@AllArgsConstructor
public class DiscordChannelService {
    private final JDA jda;
    private final ObservationRegistry observationRegistry;

    @Observed(name = "discord.channel.get-category")
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

    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.channel.get-or-create-category")
    public Category getOrCreateCategory(Guild guild, String name) {
        List<Category> existing = guild.getCategoriesByName(name, true);
        if (!existing.isEmpty()) return existing.get(0);
        return guild.createCategory(name).complete();
    }

    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.channel.create-text")
    public TextChannel createTextChannel(Category category, String name) {
        return category.createTextChannel(name).setPosition(99).complete();
    }

    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.channel.create-private-text")
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

    @Observed(name = "discord.channel.delete")
    public void deleteChannel(long channelId) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel != null)
            JdaObservations.queue(channel.delete(), "discord.channel.delete.queue", observationRegistry);
    }

    @Observed(name = "discord.channel.rename")
    public void setChannelName(long channelId, String name) {
        TextChannel channel = getTextChannel(channelId);
        if (channel != null && !channel.getName().equals(name)) {
            JdaObservations.queue(
                    channel.getManager().setName(name), "discord.channel.rename.queue", observationRegistry);
        }
    }

    @Observed(name = "discord.channel.move-to-category")
    public void moveChannelToCategory(TextChannel channel, Category target) {
        JdaObservations.queue(
                channel.getManager().setParent(target).sync(),
                "discord.channel.move-to-category.queue",
                observationRegistry);
    }

    @Observed(name = "discord.channel.sort-by-name")
    public void sortChannelsByChannelName(Category category) {
        reorderChannels(category, ChannelOrdering.byChannelName());
    }

    /**
     * Apply {@code order} to a category's text channels with a single bulk reorder, skipping the
     * Discord round-trip entirely when the channels are already in the desired order.
     *
     * <p>Replaces the previous per-channel {@code setPosition} loop, which issued one PATCH per
     * channel on every run regardless of whether anything had actually moved.
     */
    @Observed(name = "discord.channel.reorder")
    public void reorderChannels(Category category, Comparator<GuildChannel> order) {
        List<TextChannel> current = category.getTextChannels();
        List<TextChannel> desired = new ArrayList<>(current);
        desired.sort(order);

        List<Long> currentIds = current.stream().map(TextChannel::getIdLong).toList();
        List<Long> desiredIds = desired.stream().map(TextChannel::getIdLong).toList();
        if (currentIds.equals(desiredIds)) {
            return;
        }

        JdaObservations.queue(
                category.modifyTextChannelPositions().sortOrder(order),
                "discord.channel.reorder.queue",
                observationRegistry);
    }
}
