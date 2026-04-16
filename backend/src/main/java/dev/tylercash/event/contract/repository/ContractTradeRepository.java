package dev.tylercash.event.contract.repository;

import dev.tylercash.event.contract.model.ContractTrade;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractTradeRepository extends JpaRepository<ContractTrade, Long> {
    List<ContractTrade> findByContractIdOrderByTradedAtAsc(UUID contractId);

    List<ContractTrade> findByContractIdAndSnowflake(UUID contractId, String snowflake);
}
