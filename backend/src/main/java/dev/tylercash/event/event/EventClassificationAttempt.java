package dev.tylercash.event.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "event_classification_attempt")
@Getter
@Setter
@NoArgsConstructor
public class EventClassificationAttempt {

    @Id
    private UUID eventId;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private ZonedDateTime firstAttemptAt;

    @Column(nullable = false)
    private ZonedDateTime nextRetryAt;
}
