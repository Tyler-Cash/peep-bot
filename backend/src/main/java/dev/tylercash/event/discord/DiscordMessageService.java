package dev.tylercash.event.discord;

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

    public Message sendMessage(TextChannel channel, String content) {
        return channel.sendMessage(content).complete();
    }

    public Message sendEmbed(
            TextChannel channel, String content, Collection<MessageEmbed> embeds, List<ActionRow> components) {
        MessageCreateBuilder builder =
                new MessageCreateBuilder().addContent(content).addEmbeds(embeds);
        if (components != null && !components.isEmpty()) {
            builder.addComponents(components);
        }
        Message message = channel.sendMessage(builder.build()).complete();
        message.pin().queue();
        return message;
    }

    public Message sendWithAttachment(TextChannel channel, String content, byte[] data, String filename) {
        return channel.sendMessage(content)
                .addFiles(FileUpload.fromData(data, filename))
                .complete();
    }

    public Message sendEmbedWithAttachment(
            TextChannel channel, Collection<MessageEmbed> embeds, byte[] data, String filename) {
        MessageCreateBuilder builder = new MessageCreateBuilder().addEmbeds(embeds);
        Message message = channel.sendMessage(builder.build())
                .addFiles(FileUpload.fromData(data, filename))
                .complete();
        message.pin().queue();
        return message;
    }

    public void editEmbedWithAttachment(
            long channelId, long messageId, Collection<MessageEmbed> embeds, byte[] data, String filename) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel == null) return;
        channel.editMessageById(
                        messageId,
                        new MessageEditBuilder()
                                .setEmbeds(embeds)
                                .setFiles(FileUpload.fromData(data, filename))
                                .build())
                .queue();
    }

    public void editEmbeds(long channelId, long messageId, Collection<MessageEmbed> embeds) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel == null) return;
        channel.editMessageEmbedsById(messageId, embeds).queue();
    }

    public void editComponents(long channelId, long messageId, List<ActionRow> components) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel == null) return;
        if (components == null || components.isEmpty()) {
            channel.editMessageComponentsById(messageId).queue();
        } else {
            channel.editMessageComponentsById(messageId, components).queue();
        }
    }

    public void deleteMessage(long channelId, long messageId) {
        TextChannel channel = jda.getChannelById(TextChannel.class, channelId);
        if (channel != null) channel.deleteMessageById(messageId).queue();
    }
}
