package dev.tylercash.event.contract.model;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "user_balance")
@NoArgsConstructor
public class UserBalance {
    @Id
    private String snowflake;

    @Column(nullable = false)
    private long balance;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
