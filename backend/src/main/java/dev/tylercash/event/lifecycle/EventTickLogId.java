package dev.tylercash.event.lifecycle;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class EventTickLogId implements Serializable {
    private UUID eventId;
    private String tickType;

    public EventTickLogId() {}

    public EventTickLogId(UUID eventId, String tickType) {
        this.eventId = eventId;
        this.tickType = tickType;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof EventTickLogId i)) return false;
        return Objects.equals(eventId, i.eventId) && Objects.equals(tickType, i.tickType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, tickType);
    }
}
