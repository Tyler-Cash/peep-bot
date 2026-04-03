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

    public EventDetailDto(Event event, boolean completed, AttendanceSummary summary, Map<String, String> nameMap) {
        super(event);
        this.accepted = toSortedRecordList(summary.accepted(), nameMap);
        this.declined = toSortedRecordList(summary.declined(), nameMap);
        this.maybe = toSortedRecordList(summary.maybe(), nameMap);
        this.completed = completed;
        this.hasPrivateChannel = event.getPrivateChannelId() != null;
    }

    private static List<AttendeeDto> toSortedList(Set<Attendee> attendees) {
        return attendees.stream()
                .map(AttendeeDto::new)
                .sorted(Comparator.comparing(a -> a.getInstant() != null ? a.getInstant() : java.time.Instant.EPOCH))
                .toList();
    }

    private static List<AttendeeDto> toSortedRecordList(List<AttendanceRecord> records, Map<String, String> nameMap) {
        return records.stream()
                .map(r -> {
                    String displayName;
                    if (r.getSnowflake() != null) {
                        displayName = nameMap.getOrDefault(r.getSnowflake(), r.getSnowflake());
                    } else {
                        displayName = r.getName();
                    }
                    return new AttendeeDto(r, displayName);
                })
                .sorted(Comparator.comparing(a -> a.getInstant() != null ? a.getInstant() : java.time.Instant.EPOCH))
                .toList();
    }
}
