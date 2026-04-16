package dev.tylercash.event.contract.repository;

import dev.tylercash.event.contract.model.UserBalance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBalanceRepository extends JpaRepository<UserBalance, String> {}
