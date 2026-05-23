package dev.tylercash.event.discord;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import java.util.Collection;
import java.util.List;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class DiscordMessageService {
    private final JDA jda;
    private final ObservationRegistry observationRegistry;

    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.message.send")
    public Message sendMessage(TextChannel channel, String content) {
        return channel.sendMessage(content).complete();
    }

    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.message.send-embed")
    public Message sendEmbed(
            TextChannel channel, String content, Collection<MessageEmbed> embeds, List<ActionRow> components) {
        MessageCreateBuilder builder =
                new MessageCreateBuilder().addContent(content).addEmbeds(embeds);
        if (components != null && !components.isEmpty()) {
            builder.addComponents(components);
        }
        Message message = channel.sendMessage(builder.build()).complete();
        JdaObservations.queue(message.pin(), "discord.message.pin.queue", observationRegistry);
        return message;
    }

    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.message.send-with-attachment")
    public Message sendWithAttachment(TextChannel channel, String content, byte[] data, String filename) {
        return channel.sendMessage(content)
                .addFiles(FileUpload.fromData(data, filename))
                .complete();
    }

    @CircuitBreaker(name = "discord")
    @Observed(name = "discord.message.send-embed-with-attachment")
    public Message sendEmbedWithAttachment(
            TextChannel channel, Collection<MessageEmbed> embeds, byte[] data, String filename) {
        MessageCreateBuilder builder = new MessageCreateBuilder().addEmbeds(embeds);
        Message message = channel.sendMessage(builder.build())
                .addFiles(FileUpload.fromData(data, filename))
                .complete();
        JdaObservations.queue(message.pin(), "discord.message.pin.queue", observationRegistry);
        return message;
    }

    @Observed(name = "discord.message.edit-embed-with-attachment")
    public void editEmbedWithAttachment(
            long channelId, long messageId, Collection<MessageEmbed> embeds, byte[] data, String filename) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel == null) return;
        JdaObservations.queue(
                channel.editMessageById(
                        messageId,
                        new MessageEditBuilder()
                                .setEmbeds(embeds)
                                .setFiles(FileUpload.fromData(data, filename))
                                .build()),
                "discord.message.edit-embed-with-attachment.queue",
                observationRegistry);
    }

    @Observed(name = "discord.message.edit-embeds")
    public void editEmbeds(long channelId, long messageId, Collection<MessageEmbed> embeds) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel == null) return;
        JdaObservations.queue(
                channel.editMessageEmbedsById(messageId, embeds),
                "discord.message.edit-embeds.queue",
                observationRegistry);
    }

    @Observed(name = "discord.message.edit-components")
    public void editComponents(long channelId, long messageId, List<ActionRow> components) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel == null) return;
        if (components == null || components.isEmpty()) {
            JdaObservations.queue(
                    channel.editMessageComponentsById(messageId),
                    "discord.message.clear-components.queue",
                    observationRegistry);
        } else {
            JdaObservations.queue(
                    channel.editMessageComponentsById(messageId, components),
                    "discord.message.edit-components.queue",
                    observationRegistry);
        }
    }

    @Observed(name = "discord.message.delete")
    public void deleteMessage(long channelId, long messageId) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel != null)
            JdaObservations.queue(
                    channel.deleteMessageById(messageId), "discord.message.delete.queue", observationRegistry);
    }
}
