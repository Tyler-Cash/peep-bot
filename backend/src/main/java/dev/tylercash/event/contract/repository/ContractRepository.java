package dev.tylercash.event.contract.repository;

import dev.tylercash.event.contract.model.Contract;
import dev.tylercash.event.contract.model.ContractState;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractRepository extends JpaRepository<Contract, UUID> {
    List<Contract> findByStateIn(List<ContractState> states);
}
