package dev.tylercash.event.contract.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "contract")
@NoArgsConstructor
public class Contract {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContractState state = ContractState.CREATED;

    @Column(name = "creator_snowflake", nullable = false)
    private String creatorSnowflake;

    @Column(name = "channel_id")
    private Long channelId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "server_id")
    private Long serverId;

    @Column(name = "b_parameter", nullable = false)
    private double bParameter;

    @Column(name = "seed_amount", nullable = false)
    private long seedAmount;

    @Column(name = "winning_outcome_id")
    private UUID winningOutcomeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "contract", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<ContractOutcome> outcomes = new ArrayList<>();
}
