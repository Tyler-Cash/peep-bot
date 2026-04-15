package dev.tylercash.event.discord.listener;

import static org.mockito.Mockito.*;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.immich.ImmichConfiguration;
import dev.tylercash.event.immich.ImmichService;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.utils.NamedAttachmentProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageReceivedListenerTest {

    private Clock clock;
    private EventRepository eventRepository;
    private ImmichConfiguration immichConfiguration;
    private ImmichService immichService;
    private MessageReceivedListener listener;

    @BeforeEach
    void setUp() {
        // Clock fixed at 15:00 UTC — after a 13:00 event start
        clock = Clock.fixed(Instant.parse("2025-01-01T15:00:00Z"), ZoneId.of("UTC"));
        eventRepository = mock(EventRepository.class);
        immichConfiguration = new ImmichConfiguration();
        immichConfiguration.setEnabled(true);
        immichService = mock(ImmichService.class);
        listener = new MessageReceivedListener(clock, eventRepository, immichConfiguration, immichService);
    }

    private MessageReceivedEvent buildEvent(long channelId, List<Message.Attachment> attachments) {
        MessageReceivedEvent jdaEvent = mock(MessageReceivedEvent.class);
        Message message = mock(Message.class);
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        net.dv8tion.jda.api.entities.User author = mock(net.dv8tion.jda.api.entities.User.class);
        when(jdaEvent.isWebhookMessage()).thenReturn(false);
        when(jdaEvent.getAuthor()).thenReturn(author);
        when(author.isBot()).thenReturn(false);
        when(jdaEvent.getMessage()).thenReturn(message);
        when(jdaEvent.getChannel()).thenReturn(channel);
        when(channel.getIdLong()).thenReturn(channelId);
        when(message.getAttachments()).thenReturn(attachments);
        return jdaEvent;
    }

    private Message.Attachment imageAttachment(String filename, byte[] data) {
        Message.Attachment attachment = mock(Message.Attachment.class);
        NamedAttachmentProxy proxy = mock(NamedAttachmentProxy.class);
        when(attachment.getContentType()).thenReturn("image/jpeg");
        when(attachment.getFileName()).thenReturn(filename);
        when(attachment.getProxy()).thenReturn(proxy);
        when(proxy.download()).thenReturn(CompletableFuture.completedFuture(new ByteArrayInputStream(data)));
        return attachment;
    }

    private Event startedEventWithAlbum() {
        Event event = new Event();
        event.setImmichAlbumId("album-123");
        event.setDateTime(ZonedDateTime.parse("2025-01-01T13:00:00Z")); // started 2h ago
        return event;
    }

    @Test
    void skipsWhenImmichDisabled() {
        immichConfiguration.setEnabled(false);
        listener.onMessageReceived(buildEvent(99L, List.of(mock(Message.Attachment.class))));
        verifyNoInteractions(eventRepository, immichService);
    }

    @Test
    void skipsMessagesFromBots() {
        MessageReceivedEvent jdaEvent = mock(MessageReceivedEvent.class);
        net.dv8tion.jda.api.entities.User author = mock(net.dv8tion.jda.api.entities.User.class);
        when(jdaEvent.isWebhookMessage()).thenReturn(false);
        when(jdaEvent.getAuthor()).thenReturn(author);
        when(author.isBot()).thenReturn(true);

        listener.onMessageReceived(jdaEvent);

        verifyNoInteractions(eventRepository, immichService);
    }

    @Test
    void skipsWhenNoAttachments() {
        listener.onMessageReceived(buildEvent(99L, Collections.emptyList()));
        verifyNoInteractions(eventRepository, immichService);
    }

    @Test
    void skipsWhenChannelHasNoMatchingEvent() {
        when(eventRepository.findByChannelId(99L)).thenReturn(null);
        listener.onMessageReceived(buildEvent(99L, List.of(mock(Message.Attachment.class))));
        verifyNoInteractions(immichService);
    }

    @Test
    void skipsWhenEventHasNoAlbumId() {
        Event event = new Event();
        event.setImmichAlbumId(null);
        event.setDateTime(ZonedDateTime.parse("2025-01-01T13:00:00Z"));
        when(eventRepository.findByChannelId(99L)).thenReturn(event);
        listener.onMessageReceived(buildEvent(99L, List.of(mock(Message.Attachment.class))));
        verifyNoInteractions(immichService);
    }

    @Test
    void skipsWhenEventHasNotStartedYet() {
        Event event = new Event();
        event.setImmichAlbumId("album-123");
        event.setDateTime(ZonedDateTime.parse("2025-01-01T18:00:00Z")); // in the future
        when(eventRepository.findByChannelId(99L)).thenReturn(event);
        listener.onMessageReceived(buildEvent(99L, List.of(mock(Message.Attachment.class))));
        verifyNoInteractions(immichService);
    }

    @Test
    void skipsNonMediaAttachments() {
        Event event = startedEventWithAlbum();
        when(eventRepository.findByChannelId(99L)).thenReturn(event);

        Message.Attachment docAttachment = mock(Message.Attachment.class);
        when(docAttachment.getContentType()).thenReturn("application/pdf");

        listener.onMessageReceived(buildEvent(99L, List.of(docAttachment)));
        verifyNoInteractions(immichService);
    }

    @Test
    void uploadsImageAndAddsToAlbum() {
        Event event = startedEventWithAlbum();
        when(eventRepository.findByChannelId(99L)).thenReturn(event);

        byte[] imageData = new byte[] {1, 2, 3};
        Message.Attachment attachment = imageAttachment("photo.jpg", imageData);

        when(immichService.uploadAsset("photo.jpg", imageData, "image/jpeg")).thenReturn(Optional.of("asset-456"));

        listener.onMessageReceived(buildEvent(99L, List.of(attachment)));

        verify(immichService).uploadAsset("photo.jpg", imageData, "image/jpeg");
        verify(immichService).addAssetsToAlbum("album-123", List.of("asset-456"));
    }

    @Test
    void uploadsMultipleAttachmentsInOneBatch() {
        Event event = startedEventWithAlbum();
        when(eventRepository.findByChannelId(99L)).thenReturn(event);

        byte[] data1 = new byte[] {1};
        byte[] data2 = new byte[] {2};
        Message.Attachment att1 = imageAttachment("a.jpg", data1);
        Message.Attachment att2 = imageAttachment("b.jpg", data2);

        when(immichService.uploadAsset("a.jpg", data1, "image/jpeg")).thenReturn(Optional.of("id-1"));
        when(immichService.uploadAsset("b.jpg", data2, "image/jpeg")).thenReturn(Optional.of("id-2"));

        listener.onMessageReceived(buildEvent(99L, List.of(att1, att2)));

        verify(immichService).addAssetsToAlbum("album-123", List.of("id-1", "id-2"));
    }

    @Test
    void uploadsVideoAttachment() {
        Event event = startedEventWithAlbum();
        when(eventRepository.findByChannelId(99L)).thenReturn(event);

        Message.Attachment attachment = mock(Message.Attachment.class);
        NamedAttachmentProxy proxy = mock(NamedAttachmentProxy.class);
        byte[] videoData = new byte[] {9, 8, 7};
        when(attachment.getContentType()).thenReturn("video/mp4");
        when(attachment.getFileName()).thenReturn("clip.mp4");
        when(attachment.getProxy()).thenReturn(proxy);
        when(proxy.download()).thenReturn(CompletableFuture.completedFuture(new ByteArrayInputStream(videoData)));
        when(immichService.uploadAsset("clip.mp4", videoData, "video/mp4")).thenReturn(Optional.of("asset-vid"));

        listener.onMessageReceived(buildEvent(99L, List.of(attachment)));

        verify(immichService).uploadAsset("clip.mp4", videoData, "video/mp4");
        verify(immichService).addAssetsToAlbum("album-123", List.of("asset-vid"));
    }

    @Test
    void doesNotCallAddAssetsWhenAllUploadsFail() {
        Event event = startedEventWithAlbum();
        when(eventRepository.findByChannelId(99L)).thenReturn(event);

        byte[] imageData = new byte[] {1};
        Message.Attachment attachment = imageAttachment("photo.jpg", imageData);
        when(immichService.uploadAsset("photo.jpg", imageData, "image/jpeg")).thenReturn(Optional.empty());

        listener.onMessageReceived(buildEvent(99L, List.of(attachment)));

        verify(immichService).uploadAsset("photo.jpg", imageData, "image/jpeg");
        verify(immichService, never()).addAssetsToAlbum(any(), any());
    }
}
