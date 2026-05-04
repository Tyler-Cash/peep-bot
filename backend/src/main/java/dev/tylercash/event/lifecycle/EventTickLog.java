package dev.tylercash.event.lifecycle;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "event_tick_log")
@IdClass(EventTickLogId.class)
@Data
@NoArgsConstructor
public class EventTickLog {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Id
    @Column(name = "tick_type")
    private String tickType;

    @Column(name = "fired_at", nullable = false)
    private Instant firedAt;

    @PrePersist
    void onCreate() {
        if (firedAt == null) firedAt = Instant.now();
    }
}
