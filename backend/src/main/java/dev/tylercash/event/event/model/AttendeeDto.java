package dev.tylercash.event.event.model;

import lombok.Data;

import java.time.Instant;

@Data
public class AttendeeDto {
    private final String snowflake;
    private final String name;
    private final Instant instant;

    public AttendeeDto(Attendee attendee) {
        this.snowflake = attendee.getSnowflake();
        this.name = attendee.getName();
        this.instant = attendee.getInstant();
    }
}
