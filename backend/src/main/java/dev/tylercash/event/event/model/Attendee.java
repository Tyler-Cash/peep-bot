package dev.tylercash.event.event.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Attendee implements Serializable {
    String snowflake;
    String name;

    public static Attendee createDiscordAttendee(String snowflake) {
        return new Attendee(snowflake, null);
    }

    public static Attendee createNamedAttendee(String name) {
        return new Attendee(null, name);
    }
}
