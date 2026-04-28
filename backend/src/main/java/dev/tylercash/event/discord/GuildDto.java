package dev.tylercash.event.discord;

public record GuildDto(
        String id,
        String name,
        String initials,
        String iconUrl,
        String color,
        String channel,
        int members,
        Double primaryLocationLat,
        Double primaryLocationLng) {}
