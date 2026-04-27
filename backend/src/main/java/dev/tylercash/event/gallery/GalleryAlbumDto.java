package dev.tylercash.event.gallery;

import java.time.ZonedDateTime;
import java.util.UUID;

public record GalleryAlbumDto(
        UUID eventId,
        String eventName,
        ZonedDateTime eventDateTime,
        String albumId,
        String thumbnailUrl,
        int assetCount) {}
