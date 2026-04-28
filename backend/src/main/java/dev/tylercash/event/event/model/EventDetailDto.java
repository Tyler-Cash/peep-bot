package dev.tylercash.event.event.model;

import dev.tylercash.event.discord.model.DiscordUserCache;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class EventDetailDto extends EventDto {
    private List<AttendeeDto> accepted;
    private List<AttendeeDto> declined;
    private List<AttendeeDto> maybe;
    private boolean completed;
    private boolean hasPrivateChannel;

    public EventDetailDto(Event event, boolean completed) {
        super(event);
        this.accepted = toSortedList(event.getAccepted());
        this.declined = toSortedList(event.getDeclined());
        this.maybe = toSortedList(event.getMaybe());
        this.completed = completed;
        this.hasPrivateChannel = event.getPrivateChannelId() != null;
    }

    public EventDetailDto(
            Event event, boolean completed, AttendanceSummary summary, Map<String, DiscordUserCache> userMap) {
        this(event, completed, summary, userMap, "unknown");
    }

    public EventDetailDto(
            Event event,
            boolean completed,
            AttendanceSummary summary,
            Map<String, DiscordUserCache> userMap,
            String category) {
        super(event);
        this.setCategory(category == null || category.isBlank() ? "unknown" : category);
        String creator = event.getCreator();
        if (creator != null && !creator.isBlank()) {
            DiscordUserCache user = userMap.get(creator);
            this.setHost(user != null ? user.getDisplayName() : creator);
            this.setHostUsername(user != null ? user.getUsername() : null);
            this.setHostAvatarUrl("/api/avatar/" + creator);
        }
        this.accepted = toSortedRecordList(summary.accepted(), userMap);
        this.declined = toSortedRecordList(summary.declined(), userMap);
        this.maybe = toSortedRecordList(summary.maybe(), userMap);
        this.completed = completed;
        this.hasPrivateChannel = event.getPrivateChannelId() != null;
    }

    private static List<AttendeeDto> toSortedList(Set<Attendee> attendees) {
        return attendees.stream()
                .map(AttendeeDto::new)
                .sorted(Comparator.comparing(a -> a.getInstant() != null ? a.getInstant() : java.time.Instant.EPOCH))
                .toList();
    }

    private static List<AttendeeDto> toSortedRecordList(
            List<AttendanceRecord> records, Map<String, DiscordUserCache> userMap) {
        return records.stream()
                .map(r -> {
                    String displayName;
                    String username = null;
                    if (r.getSnowflake() != null) {
                        DiscordUserCache user = userMap.get(r.getSnowflake());
                        displayName = user != null ? user.getDisplayName() : r.getSnowflake();
                        username = user != null ? user.getUsername() : null;
                    } else {
                        displayName = r.getName();
                    }
                    return new AttendeeDto(r, displayName, username);
                })
                .sorted(Comparator.comparing(a -> a.getInstant() != null ? a.getInstant() : java.time.Instant.EPOCH))
                .toList();
    }
}
