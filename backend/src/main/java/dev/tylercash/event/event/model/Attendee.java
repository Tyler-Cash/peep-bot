package dev.tylercash.event.event.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Attendee implements Serializable {
    private String snowflake;
    private String name;
    private Instant instant;

    public static Attendee createDiscordAttendee(String snowflake, String name) {
        return new Attendee(snowflake, name, Instant.now());
    }

    public static Attendee createNamedAttendee(String name) {
        return new Attendee(null, name, Instant.now());
    }

    @Override
    public int hashCode() {
        int hash;
        if (snowflake == null) {
            hash = Objects.hash(name);
        } else {
            hash = Objects.hash(snowflake, name);
        }
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }

        if (!(o instanceof Attendee other)) {
            return false;
        }

        return (snowflake == null && other.getSnowflake() == null && name.equals(other.getName())) ||
                (snowflake != null && snowflake.equalsIgnoreCase(other.getSnowflake()));
    }
}
