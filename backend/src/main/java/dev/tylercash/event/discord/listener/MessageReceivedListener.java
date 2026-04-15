package dev.tylercash.event.discord.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.immich.ImmichConfiguration;
import dev.tylercash.event.immich.ImmichService;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageReceivedListener extends ListenerAdapter {

    private final Clock clock;
    private final EventRepository eventRepository;
    private final ImmichConfiguration immichConfiguration;
    private final ImmichService immichService;

    @Override
    public void onMessageReceived(@NonNull MessageReceivedEvent event) {
        if (!immichConfiguration.isEnabled()) {
            return;
        }
        if (event.isWebhookMessage() || event.getAuthor().isBot()) {
            return;
        }
        Message message = event.getMessage();
        List<Message.Attachment> attachments = message.getAttachments();
        if (attachments.isEmpty()) {
            return;
        }
        Event dbEvent = eventRepository.findByChannelId(event.getChannel().getIdLong());
        if (dbEvent == null || dbEvent.getImmichAlbumId() == null) {
            return;
        }
        if (ZonedDateTime.now(clock).isBefore(dbEvent.getDateTime())) {
            return;
        }
        List<String> assetIds = new ArrayList<>();
        for (Message.Attachment attachment : attachments) {
            String contentType = attachment.getContentType();
            if (contentType == null || (!contentType.startsWith("image/") && !contentType.startsWith("video/"))) {
                continue;
            }
            try {
                byte[] data = attachment.getProxy().download().join().readAllBytes();
                immichService
                        .uploadAsset(attachment.getFileName(), data, contentType)
                        .ifPresent(assetIds::add);
            } catch (Exception e) {
                log.warn("Failed to download attachment {} from Discord", attachment.getFileName(), e);
            }
        }
        if (!assetIds.isEmpty()) {
            immichService.addAssetsToAlbum(dbEvent.getImmichAlbumId(), assetIds);
        }
    }
}
