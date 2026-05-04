package dev.tylercash.event.lifecycle;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ListenerInvocationId implements Serializable {
    private UUID eventId;
    private String lifecycleEventType;
    private String listenerName;

    public ListenerInvocationId() {}

    public ListenerInvocationId(UUID eventId, String lifecycleEventType, String listenerName) {
        this.eventId = eventId;
        this.lifecycleEventType = lifecycleEventType;
        this.listenerName = listenerName;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ListenerInvocationId i)) return false;
        return Objects.equals(eventId, i.eventId)
                && Objects.equals(lifecycleEventType, i.lifecycleEventType)
                && Objects.equals(listenerName, i.listenerName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, lifecycleEventType, listenerName);
    }
}
