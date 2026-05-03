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
        String emojiMaybe) {}
