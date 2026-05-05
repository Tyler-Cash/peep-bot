package dev.tylercash.event.lifecycle;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "listener_invocation")
@IdClass(ListenerInvocationId.class)
@Data
@NoArgsConstructor
public class ListenerInvocation {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Id
    @Column(name = "lifecycle_event_type")
    private String lifecycleEventType;

    @Id
    @Column(name = "listener_name")
    private String listenerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ListenerInvocationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
