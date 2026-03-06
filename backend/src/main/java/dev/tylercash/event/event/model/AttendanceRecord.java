package dev.tylercash.event.event.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "attendance")
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    private String snowflake;

    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttendanceStatus status;

    @Column(name = "owner_snowflake")
    private String ownerSnowflake;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    public AttendanceRecord(
            UUID eventId, String snowflake, String name, AttendanceStatus status, String ownerSnowflake) {
        this.eventId = eventId;
        this.snowflake = snowflake;
        this.name = name;
        this.status = status;
        this.ownerSnowflake = ownerSnowflake;
        this.recordedAt = Instant.now();
    }
}
