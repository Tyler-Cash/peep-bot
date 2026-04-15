package dev.tylercash.event.contract.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

@Data
@Entity
@Table(name = "contract_trade")
@NoArgsConstructor
public class ContractTrade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "outcome_id", nullable = false)
    private UUID outcomeId;

    @Column(nullable = false)
    private String snowflake;

    @Column(name = "shares_bought", nullable = false)
    private double sharesBought;

    @Column(name = "cost_paid", nullable = false)
    private long costPaid;

    @Type(JsonBinaryType.class)
    @Column(name = "prob_before", columnDefinition = "jsonb", nullable = false)
    private JsonNode probBefore;

    @Column(name = "traded_at", nullable = false)
    private Instant tradedAt;
}
