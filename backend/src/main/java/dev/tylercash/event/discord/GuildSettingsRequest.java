package dev.tylercash.event.discord;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import java.util.Set;

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
        Boolean anyoneCanCreate) {

    private static final Set<Integer> ALLOWED_ARCHIVE_DAYS = Set.of(7, 14, 30, 90);
    private static final Set<Integer> ALLOWED_RATE_LIMIT = Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

    @AssertTrue(message = "archiveDays must be one of {7, 14, 30, 90}")
    @JsonIgnore
    public boolean isArchiveDaysValid() {
        return archiveDays == null || ALLOWED_ARCHIVE_DAYS.contains(archiveDays);
    }

    @AssertTrue(message = "eventCreateRateLimitPerHour must be 1..10 or null")
    @JsonIgnore
    public boolean isRateLimitValid() {
        return eventCreateRateLimitPerHour == null || ALLOWED_RATE_LIMIT.contains(eventCreateRateLimitPerHour);
    }

    @AssertTrue(message = "planned and archived categories must differ")
    @JsonIgnore
    public boolean isCategoriesDistinct() {
        return plannedCategoryId == null || archivedCategoryId == null || !plannedCategoryId.equals(archivedCategoryId);
    }
}
