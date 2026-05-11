package dev.tylercash.event.discord;

public record GuildSettingsRequest(
        String primaryLocationPlaceId,
        String primaryLocationName,
        Double primaryLocationLat,
        Double primaryLocationLng,
        String eventsRole,
        String organiserRole,
        String separatorChannel,
        String emojiAccepted,
        String emojiDeclined,
        String emojiMaybe,
        Integer eventCreateRateLimitPerHour,
        String plannedCategoryId,
        String archivedCategoryId,
        Integer archiveDays,
        Boolean anyoneCanCreate) {}
