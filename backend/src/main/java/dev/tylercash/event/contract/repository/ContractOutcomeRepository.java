package dev.tylercash.event.contract.repository;

import dev.tylercash.event.contract.model.ContractOutcome;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractOutcomeRepository extends JpaRepository<ContractOutcome, UUID> {
    List<ContractOutcome> findByContractId(UUID contractId);
}
