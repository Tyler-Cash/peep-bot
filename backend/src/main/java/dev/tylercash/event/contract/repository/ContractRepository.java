package dev.tylercash.event.contract.repository;

import dev.tylercash.event.contract.model.Contract;
import dev.tylercash.event.contract.model.ContractState;
import dev.tylercash.event.contract.model.ContractSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContractRepository extends JpaRepository<Contract, UUID> {
    List<Contract> findByStateIn(List<ContractState> states);

    Optional<Contract> findFirstByStateInAndTitleIgnoreCase(List<ContractState> states, String title);

    @Query(
            """
            SELECT new dev.tylercash.event.contract.model.ContractSummary(c.id, c.title)
            FROM Contract c
            WHERE c.state IN :states
            AND LOWER(c.title) LIKE LOWER(CONCAT('%', :query, '%'))
            ORDER BY c.title
            """)
    List<ContractSummary> searchSummariesByStateInAndTitleContaining(
            @Param("states") List<ContractState> states, @Param("query") String query);
}
