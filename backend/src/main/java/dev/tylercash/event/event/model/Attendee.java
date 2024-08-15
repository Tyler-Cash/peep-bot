package dev.tylercash.event.event.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class Attendee implements Serializable {
    private String snowflake;
    private String name;
    @EqualsAndHashCode.Exclude
    private Instant instant = Instant.now();

    public static Attendee createDiscordAttendee(String snowflake) {
        return new Attendee(snowflake, null, Instant.now());
    }

    public static Attendee createNamedAttendee(String name) {
        return new Attendee(null, name, Instant.now());
    }
}
