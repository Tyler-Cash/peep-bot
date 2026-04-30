package dev.tylercash.event.contract;

import dev.tylercash.event.contract.model.UserBalance;
import dev.tylercash.event.contract.repository.UserBalanceRepository;
import jakarta.transaction.Transactional;
import java.time.Clock;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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
        return balance <= 0 ? config.getNegativeTradeCap() : balance;
    }

    @Transactional
    public void deduct(String snowflake, long amount) {
        ensureRowExists(snowflake);
        long floor = -config.getNegativeTradeCap();
        int updated = repo.atomicDeductIfSufficient(snowflake, amount, floor);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient balance");
        }
    }

    @Transactional
    public void credit(String snowflake, long amount) {
        ensureRowExists(snowflake);
        repo.atomicAdjust(snowflake, amount);
    }

    /**
     * Guarantees a row exists for the given snowflake, creating one with a zero balance if absent.
     * The atomic adjustment that follows will then set the correct final balance. Safe to call
     * concurrently — uses INSERT ... ON CONFLICT DO NOTHING.
     */
    private void ensureRowExists(String snowflake) {
        repo.insertIfAbsent(snowflake, 0L);
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
