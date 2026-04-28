package dev.tylercash.event.event.model;

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
            Event event,
            boolean completed,
            AttendanceSummary summary,
            Map<String, String> displayNames,
            Map<String, String> usernames) {
        this(event, completed, summary, displayNames, usernames, "unknown");
    }

    public EventDetailDto(
            Event event,
            boolean completed,
            AttendanceSummary summary,
            Map<String, String> displayNames,
            Map<String, String> usernames,
            String category) {
        super(event);
        this.setCategory(category == null || category.isBlank() ? "unknown" : category);
        String creator = event.getCreator();
        if (creator != null && !creator.isBlank()) {
            String name = displayNames.get(creator);
            this.setHost(name != null ? name : creator);
            this.setHostUsername(usernames.get(creator));
            this.setHostAvatarUrl("/api/avatar/" + creator);
        }
        this.accepted = toSortedRecordList(summary.accepted(), displayNames, usernames);
        this.declined = toSortedRecordList(summary.declined(), displayNames, usernames);
        this.maybe = toSortedRecordList(summary.maybe(), displayNames, usernames);
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
            List<AttendanceRecord> records, Map<String, String> displayNames, Map<String, String> usernames) {
        return records.stream()
                .map(r -> {
                    String displayName;
                    String username = null;
                    if (r.getSnowflake() != null) {
                        String resolved = displayNames.get(r.getSnowflake());
                        displayName = resolved != null ? resolved : r.getSnowflake();
                        username = usernames.get(r.getSnowflake());
                    } else {
                        displayName = r.getName();
                    }
                    return new AttendeeDto(r, displayName, username);
                })
                .sorted(Comparator.comparing(a -> a.getInstant() != null ? a.getInstant() : java.time.Instant.EPOCH))
                .toList();
    }
}
