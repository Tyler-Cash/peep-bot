package dev.tylercash.event.discord;

public record GuildSettingsDto(
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
        int archiveDays,
        boolean anyoneCanCreate,
        int defaultEventCreateRateLimitPerHour) {

    public static GuildSettingsDto from(Guild row, int defaultEventCreateRateLimitPerHour) {
        return new GuildSettingsDto(
                row.getPrimaryLocationPlaceId(),
                row.getPrimaryLocationName(),
                row.getPrimaryLocationLat(),
                row.getPrimaryLocationLng(),
                row.getEventsRole(),
                row.getOrganiserRole(),
                row.getSeparatorChannel(),
                row.getEmojiAccepted(),
                row.getEmojiDeclined(),
                row.getEmojiMaybe(),
                row.getEventCreateRateLimitPerHour(),
                row.getPlannedCategoryId(),
                row.getArchivedCategoryId(),
                row.getArchiveDays(),
                row.isAnyoneCanCreate(),
                defaultEventCreateRateLimitPerHour);
    }
}
