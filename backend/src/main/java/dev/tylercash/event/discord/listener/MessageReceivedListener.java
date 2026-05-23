package dev.tylercash.event.discord.listener;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.immich.ImmichConfiguration;
import dev.tylercash.event.immich.ImmichService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MessageReceivedListener extends ListenerAdapter {

    private final Clock clock;
    private final EventRepository eventRepository;
    private final ImmichConfiguration immichConfiguration;
    private final ImmichService immichService;
    private final ObservationRegistry observationRegistry;
    private final Executor executor;

    public MessageReceivedListener(
            Clock clock,
            EventRepository eventRepository,
            ImmichConfiguration immichConfiguration,
            ImmichService immichService,
            ObservationRegistry observationRegistry,
            @Qualifier("discordListenerExecutor") Executor executor) {
        this.clock = clock;
        this.eventRepository = eventRepository;
        this.immichConfiguration = immichConfiguration;
        this.immichService = immichService;
        this.observationRegistry = observationRegistry;
        this.executor = executor;
    }

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
        long channelId = event.getChannel().getIdLong();
        Event dbEvent = eventRepository.findByChannelId(channelId);
        if (dbEvent == null) {
            log.debug("Message with attachments in channel {} has no matching event — skipping", channelId);
            return;
        }
        if (dbEvent.getImmichAlbumId() == null) {
            log.info("Event '{}' has no Immich album yet — skipping attachment upload", dbEvent.getName());
            return;
        }
        if (ZonedDateTime.now(clock).isBefore(dbEvent.getDateTime())) {
            log.info("Event '{}' has not started yet — skipping attachment upload", dbEvent.getName());
            return;
        }
        // Outer observation that parents the offloaded Immich upload work so each
        // immich.upload-asset span hangs off a single discord.message-received root.
        Observation parent = Observation.createNotStarted("discord.message-received", observationRegistry)
                .lowCardinalityKeyValue("attachment.count", Integer.toString(attachments.size()))
                .start();
        try (Observation.Scope ignored = parent.openScope()) {
            executor.execute(() -> {
                try (Observation.Scope inner = parent.openScope()) {
                    uploadAttachments(dbEvent, attachments);
                } catch (RuntimeException e) {
                    parent.error(e);
                    throw e;
                } finally {
                    parent.stop();
                }
            });
        }
    }

    private void uploadAttachments(Event dbEvent, List<Message.Attachment> attachments) {
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
            log.info(
                    "Uploaded {} asset(s) from event '{}' channel to Immich album {}",
                    assetIds.size(),
                    dbEvent.getName(),
                    dbEvent.getImmichAlbumId());
            immichService.addAssetsToAlbum(dbEvent.getImmichAlbumId(), assetIds);
        }
    }
}
