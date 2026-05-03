package dev.tylercash.event.event.model;

import dev.tylercash.event.discord.model.GuildMember;
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
            Map<String, GuildMember> memberMap,
            Map<String, String> usernameMap) {
        this(event, completed, summary, memberMap, usernameMap, "unknown");
    }

    public EventDetailDto(
            Event event,
            boolean completed,
            AttendanceSummary summary,
            Map<String, GuildMember> memberMap,
            Map<String, String> usernameMap,
            String category) {
        super(event);
        this.setCategory(category == null || category.isBlank() ? "unknown" : category);
        String creator = event.getCreator();
        if (creator != null && !creator.isBlank()) {
            this.setHost(resolveDisplayName(creator, memberMap, usernameMap));
            this.setHostUsername(usernameMap.get(creator));
            this.setHostAvatarUrl("/api/avatar/" + creator);
        }
        this.accepted = toSortedRecordList(summary.accepted(), memberMap, usernameMap);
        this.declined = toSortedRecordList(summary.declined(), memberMap, usernameMap);
        this.maybe = toSortedRecordList(summary.maybe(), memberMap, usernameMap);
        this.completed = completed;
        this.hasPrivateChannel = event.getPrivateChannelId() != null;
    }

    private static String resolveDisplayName(
            String snowflake, Map<String, GuildMember> memberMap, Map<String, String> usernameMap) {
        GuildMember member = memberMap.get(snowflake);
        if (member != null
                && member.getDisplayName() != null
                && !member.getDisplayName().isBlank()) {
            return member.getDisplayName();
        }
        String username = usernameMap.get(snowflake);
        if (username != null && !username.isBlank()) {
            return username;
        }
        return snowflake;
    }

    private static List<AttendeeDto> toSortedList(Set<Attendee> attendees) {
        return attendees.stream()
                .map(AttendeeDto::new)
                .sorted(Comparator.comparing(a -> a.getInstant() != null ? a.getInstant() : java.time.Instant.EPOCH))
                .toList();
    }

    private static List<AttendeeDto> toSortedRecordList(
            List<AttendanceRecord> records, Map<String, GuildMember> memberMap, Map<String, String> usernameMap) {
        return records.stream()
                .map(r -> {
                    String displayName;
                    String username = null;
                    if (r.getSnowflake() != null) {
                        displayName = resolveDisplayName(r.getSnowflake(), memberMap, usernameMap);
                        username = usernameMap.get(r.getSnowflake());
                    } else {
                        displayName = r.getName();
                    }
                    return new AttendeeDto(r, displayName, username);
                })
                .sorted(Comparator.comparing(a -> a.getInstant() != null ? a.getInstant() : java.time.Instant.EPOCH))
                .toList();
    }
}
