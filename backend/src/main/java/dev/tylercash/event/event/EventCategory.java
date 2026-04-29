package dev.tylercash.event.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "event_category")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EventCategory {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "category_label", nullable = false)
    private String categoryLabel;

    @Column(name = "category_centroid_event_id")
    private UUID categoryCentroidEventId;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;
}
