package dev.tylercash.event.tfnsw;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "tfnsw_event_snapshot")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TfnswEventSnapshot {
    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "alert_ids_hash", nullable = false, length = 64)
    private String alertIdsHash;

    @Column(name = "last_posted_at")
    private Instant lastPostedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
