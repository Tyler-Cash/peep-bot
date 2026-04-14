package dev.tylercash.event.contract;

import dev.tylercash.event.contract.model.UserBalance;
import dev.tylercash.event.contract.repository.UserBalanceRepository;
import jakarta.transaction.Transactional;
import java.time.Clock;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class UserBalanceService {
    private final UserBalanceRepository repo;
    private final ContractConfiguration config;
    private final Clock clock;

    public long getBalance(String snowflake) {
        return getOrCreate(snowflake).getBalance();
    }

    public long getMaxTrade(String snowflake) {
        long balance = getBalance(snowflake);
        return balance < 0 ? config.getNegativeTradeCap() : balance;
    }

    @Transactional
    public void deduct(String snowflake, long amount) {
        UserBalance ub = getOrCreate(snowflake);
        ub.setBalance(ub.getBalance() - amount);
        ub.setUpdatedAt(clock.instant());
        repo.save(ub);
    }

    @Transactional
    public void credit(String snowflake, long amount) {
        UserBalance ub = getOrCreate(snowflake);
        ub.setBalance(ub.getBalance() + amount);
        ub.setUpdatedAt(clock.instant());
        repo.save(ub);
    }

    private UserBalance getOrCreate(String snowflake) {
        return repo.findById(snowflake).orElseGet(() -> {
            UserBalance ub = new UserBalance();
            ub.setSnowflake(snowflake);
            ub.setBalance(config.getDefaultBalance());
            ub.setUpdatedAt(clock.instant());
            return repo.save(ub);
        });
    }
}
