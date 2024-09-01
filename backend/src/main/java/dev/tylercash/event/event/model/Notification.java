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
public class Notification implements Serializable {
    private NotificationType type;
    private Instant instant;
    private long messageId;

    public Notification(NotificationType type) {
        this.type = Objects.requireNonNull(type);
    }

    @Override
    public int hashCode() {
        int hash;

        return Objects.hash(type);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }

        if (!(o instanceof Notification other)) {
            return false;
        }

        return other.getType().equals(type);
    }

}
