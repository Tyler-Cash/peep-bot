package dev.tylercash.event.contract.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Table(name = "contract_outcome")
@NoArgsConstructor
public class ContractOutcome {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", nullable = false)
    @JsonIgnore
    private Contract contract;

    @Column(nullable = false)
    private String label;

    @Column(name = "shares_outstanding", nullable = false)
    private double sharesOutstanding = 0.0;
}
