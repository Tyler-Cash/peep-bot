package dev.tylercash.event.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tylercash.event.contract.model.UserBalance;
import dev.tylercash.event.contract.repository.UserBalanceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class UserBalanceServiceTest {
    private static final String SNOWFLAKE = "u1";

    private UserBalanceRepository repo;
    private ContractConfiguration config;
    private Clock clock;
    private UserBalanceService service;

    @BeforeEach
    void setUp() {
        repo = mock(UserBalanceRepository.class);
        config = new ContractConfiguration();
        clock = Clock.fixed(Instant.parse("2026-05-01T12:00:00Z"), ZoneOffset.UTC);
        service = new UserBalanceService(repo, config, clock);
        when(repo.save(any(UserBalance.class))).thenAnswer(inv -> inv.getArgument(0));
        // By default, atomic adjust/deduct return 1 (success)
        when(repo.atomicAdjust(any(), anyLong())).thenReturn(1);
        when(repo.atomicDeductIfSufficient(any(), anyLong(), anyLong())).thenReturn(1);
    }

    @Test
    @DisplayName("getBalance creates a new user with the configured default balance on first access")
    void getBalance_createsDefaultBalanceOnFirstAccess() {
        when(repo.findById(SNOWFLAKE)).thenReturn(Optional.empty());

        long balance = service.getBalance(SNOWFLAKE);

        assertThat(balance).isEqualTo(config.getDefaultBalance());
        ArgumentCaptor<UserBalance> captor = ArgumentCaptor.forClass(UserBalance.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getSnowflake()).isEqualTo(SNOWFLAKE);
        assertThat(captor.getValue().getBalance()).isEqualTo(config.getDefaultBalance());
        assertThat(captor.getValue().getUpdatedAt()).isEqualTo(clock.instant());
    }

    @Test
    @DisplayName("getBalance returns the existing balance without creating a new record")
    void getBalance_returnsExistingBalance() {
        UserBalance existing = new UserBalance();
        existing.setSnowflake(SNOWFLAKE);
        existing.setBalance(500L);
        existing.setUpdatedAt(Instant.EPOCH);
        when(repo.findById(SNOWFLAKE)).thenReturn(Optional.of(existing));

        assertThat(service.getBalance(SNOWFLAKE)).isEqualTo(500L);
    }

    @Test
    @DisplayName("getMaxTrade returns the full balance when the user is solvent")
    void getMaxTrade_returnsBalanceWhenPositive() {
        UserBalance existing = new UserBalance();
        existing.setSnowflake(SNOWFLAKE);
        existing.setBalance(750L);
        existing.setUpdatedAt(Instant.EPOCH);
        when(repo.findById(SNOWFLAKE)).thenReturn(Optional.of(existing));

        assertThat(service.getMaxTrade(SNOWFLAKE)).isEqualTo(750L);
    }

    @Test
    @DisplayName("getMaxTrade falls back to the negative-trade cap when the user has no coins")
    void getMaxTrade_negativeBalanceFallsBackToCap() {
        UserBalance existing = new UserBalance();
        existing.setSnowflake(SNOWFLAKE);
        existing.setBalance(-200L);
        existing.setUpdatedAt(Instant.EPOCH);
        when(repo.findById(SNOWFLAKE)).thenReturn(Optional.of(existing));

        assertThat(service.getMaxTrade(SNOWFLAKE)).isEqualTo(config.getNegativeTradeCap());
    }

    @Test
    @DisplayName("deduct issues an atomic deduct with the negativeTradeCap as the floor")
    void deduct_issuesAtomicDeduct() {
        service.deduct(SNOWFLAKE, 150L);

        verify(repo).insertIfAbsent(SNOWFLAKE, 0L);
        verify(repo).atomicDeductIfSufficient(SNOWFLAKE, 150L, -config.getNegativeTradeCap());
    }

    @Test
    @DisplayName("deduct throws BAD_REQUEST when insufficient balance (0 rows updated)")
    void deduct_throwsBadRequestOnInsufficientBalance() {
        when(repo.atomicDeductIfSufficient(any(), anyLong(), anyLong())).thenReturn(0);

        assertThatThrownBy(() -> service.deduct(SNOWFLAKE, 9999L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Insufficient balance");
    }

    @Test
    @DisplayName("credit issues an atomic adjustment")
    void credit_issuesAtomicAdjust() {
        service.credit(SNOWFLAKE, 75L);

        verify(repo).insertIfAbsent(SNOWFLAKE, 0L);
        verify(repo).atomicAdjust(SNOWFLAKE, 75L);
    }

    @Test
    @DisplayName("deduct auto-creates the user row via insertIfAbsent before subtracting")
    void deduct_autoCreatesUser() {
        service.deduct(SNOWFLAKE, 100L);

        verify(repo).insertIfAbsent(eq(SNOWFLAKE), eq(0L));
        verify(repo).atomicDeductIfSufficient(eq(SNOWFLAKE), eq(100L), anyLong());
    }
}
