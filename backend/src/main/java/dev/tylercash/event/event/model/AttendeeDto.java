package dev.tylercash.event.event.model;

import java.time.Instant;
import lombok.Data;

@Data
public class AttendeeDto {
    private final String snowflake;
    private final String name;
    private final String username;
    private final Instant instant;
    private final String ownerSnowflake;
    private final String avatarUrl;

    public AttendeeDto(Attendee attendee) {
        this.snowflake = attendee.getSnowflake();
        this.name = attendee.getName();
        this.username = null;
        this.instant = attendee.getInstant();
        this.ownerSnowflake = null;
        this.avatarUrl = attendee.getSnowflake() != null ? "/api/avatar/" + attendee.getSnowflake() : null;
    }

    public AttendeeDto(AttendanceRecord record, String resolvedName) {
        this(record, resolvedName, null);
    }

    public AttendeeDto(AttendanceRecord record, String resolvedName, String username) {
        this.snowflake = record.getSnowflake();
        this.name = resolvedName;
        this.username = username;
        this.instant = record.getRecordedAt();
        this.ownerSnowflake = record.getOwnerSnowflake();
        this.avatarUrl = record.getSnowflake() != null ? "/api/avatar/" + record.getSnowflake() : null;
    }
}
