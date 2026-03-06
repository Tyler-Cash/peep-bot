package dev.tylercash.event.event.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Data
@EqualsAndHashCode(callSuper = true)
public class EventDetailDto extends EventDto {
    private List<AttendeeDto> accepted;
    private List<AttendeeDto> declined;
    private List<AttendeeDto> maybe;
    private boolean attendanceLocked;

    public EventDetailDto(Event event, boolean attendanceLocked) {
        super(event);
        this.accepted = toSortedList(event.getAccepted());
        this.declined = toSortedList(event.getDeclined());
        this.maybe = toSortedList(event.getMaybe());
        this.attendanceLocked = attendanceLocked;
    }

    private static List<AttendeeDto> toSortedList(Set<Attendee> attendees) {
        return attendees.stream()
                .map(AttendeeDto::new)
                .sorted(Comparator.comparing(a -> a.getInstant() != null ? a.getInstant() : java.time.Instant.EPOCH))
                .toList();
    }
}
