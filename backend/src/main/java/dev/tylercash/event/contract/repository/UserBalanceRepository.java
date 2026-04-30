package dev.tylercash.event.contract.repository;

import dev.tylercash.event.contract.model.UserBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBalanceRepository extends JpaRepository<UserBalance, String> {

    /**
     * Inserts a new balance row only if one does not already exist for this snowflake.
     * Used to guarantee a row exists before issuing an atomic UPDATE.
     */
    @Modifying
    @Query(
            value = "INSERT INTO user_balance (snowflake, balance, updated_at)"
                    + " VALUES (:snowflake, :balance, NOW())"
                    + " ON CONFLICT (snowflake) DO NOTHING",
            nativeQuery = true)
    void insertIfAbsent(@Param("snowflake") String snowflake, @Param("balance") long balance);

    /**
     * Atomically adjusts the balance by {@code delta} (positive to credit, negative to deduct).
     * Returns the number of rows updated (0 if no row exists).
     */
    @Modifying
    @Query(
            value = "UPDATE user_balance"
                    + " SET balance = balance + :delta, updated_at = NOW()"
                    + " WHERE snowflake = :snowflake",
            nativeQuery = true)
    int atomicAdjust(@Param("snowflake") String snowflake, @Param("delta") long delta);

    /**
     * Atomically deducts {@code amount} only when the resulting balance would stay at or above
     * {@code floor}. Returns 1 if the update succeeded, 0 if the balance check failed.
     */
    @Modifying
    @Query(
            value = "UPDATE user_balance"
                    + " SET balance = balance - :amount, updated_at = NOW()"
                    + " WHERE snowflake = :snowflake"
                    + " AND balance - :amount >= :floor",
            nativeQuery = true)
    int atomicDeductIfSufficient(
            @Param("snowflake") String snowflake, @Param("amount") long amount, @Param("floor") long floor);
}
