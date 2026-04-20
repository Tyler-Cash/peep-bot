package dev.tylercash.event.rewind.model;

import dev.tylercash.event.rewind.VectorConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "event_embedding")
@NoArgsConstructor
@AllArgsConstructor
public class EventEmbedding {
    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "name_text", nullable = false)
    private String nameText;

    @Convert(converter = VectorConverter.class)
    @Column(name = "embedding", columnDefinition = "vector(384)", nullable = false)
    private String embedding;

    @Column(name = "computed_at", nullable = false)
    private OffsetDateTime computedAt;
}
