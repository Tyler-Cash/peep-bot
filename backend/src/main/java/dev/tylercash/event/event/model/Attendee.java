package dev.tylercash.event.event.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Attendee implements Serializable {
    private String snowflake;
    private String name;
    private Instant instant;

    public static Attendee createDiscordAttendee(String snowflake, String name) {
        return new Attendee(snowflake, snowflake == null || snowflake.isEmpty() ? "[+1] " + name : name, Instant.now());
    }

    public static Attendee createWithTimestamp(String snowflake, String name, Instant instant) {
        return new Attendee(snowflake, name, instant);
    }

    @Override
    public int hashCode() {
        int hash;
        if (snowflake == null) {
            hash = Objects.hash(name);
        } else {
            hash = Objects.hash(snowflake);
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

        return (snowflake == null && other.getSnowflake() == null && name.equals(other.getName()))
                || (snowflake != null && snowflake.equalsIgnoreCase(other.getSnowflake()));
    }
}
