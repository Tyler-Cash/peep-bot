package dev.tylercash.event.event.model;

import java.time.Instant;
import lombok.Data;

@Data
public class AttendeeDto {
    private final String snowflake;
    private final String name;
    private final Instant instant;
    private final String ownerSnowflake;

    public AttendeeDto(Attendee attendee) {
        this.snowflake = attendee.getSnowflake();
        this.name = attendee.getName();
        this.instant = attendee.getInstant();
        this.ownerSnowflake = null;
    }

    public AttendeeDto(AttendanceRecord record, String resolvedName) {
        this.snowflake = record.getSnowflake();
        this.name = resolvedName;
        this.instant = record.getRecordedAt();
        this.ownerSnowflake = record.getOwnerSnowflake();
    }
}
