package dev.tylercash.event.gallery;

import dev.tylercash.event.event.model.AttendeeDto;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

public record GalleryAlbumDto(
        UUID eventId,
        String eventName,
        ZonedDateTime eventDateTime,
        String albumId,
        String thumbnailUrl,
        // Public Immich share URL (built from the stored share key). Null when
        // legacy events have no share key persisted yet — frontend falls back
        // to navigating to the event detail page in that case.
        String albumUrl,
        int assetCount,
        List<AttendeeDto> attendees) {}
