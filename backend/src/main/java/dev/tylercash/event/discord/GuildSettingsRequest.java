package dev.tylercash.event.discord;

public record GuildSettingsRequest(
        String primaryLocationPlaceId,
        String primaryLocationName,
        Double primaryLocationLat,
        Double primaryLocationLng,
        String eventsRole,
        String adminRole,
        String separatorChannel,
        String emojiAccepted,
        String emojiDeclined,
        String emojiMaybe) {}
