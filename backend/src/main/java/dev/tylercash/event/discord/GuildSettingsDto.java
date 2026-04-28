package dev.tylercash.event.discord;

public record GuildSettingsDto(
        String primaryLocationPlaceId,
        String primaryLocationName,
        Double primaryLocationLat,
        Double primaryLocationLng) {}
