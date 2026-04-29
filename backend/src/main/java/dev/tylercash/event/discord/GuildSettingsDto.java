package dev.tylercash.event.discord;

public record GuildSettingsDto(
        String primaryLocationPlaceId,
        String primaryLocationName,
        Double primaryLocationLat,
        Double primaryLocationLng,
        String eventsRole,
        String adminRole,
        String separatorChannel,
        String emojiAccepted,
        String emojiDeclined,
        String emojiMaybe) {

    public static GuildSettingsDto from(Guild row) {
        return new GuildSettingsDto(
                row.getPrimaryLocationPlaceId(),
                row.getPrimaryLocationName(),
                row.getPrimaryLocationLat(),
                row.getPrimaryLocationLng(),
                row.getEventsRole(),
                row.getAdminRole(),
                row.getSeparatorChannel(),
                row.getEmojiAccepted(),
                row.getEmojiDeclined(),
                row.getEmojiMaybe());
    }
}
