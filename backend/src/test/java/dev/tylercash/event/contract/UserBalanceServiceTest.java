package dev.tylercash.event.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    @DisplayName("deduct subtracts the amount and updates the timestamp")
    void deduct_subtractsAndTouchesUpdatedAt() {
        UserBalance existing = new UserBalance();
        existing.setSnowflake(SNOWFLAKE);
        existing.setBalance(1000L);
        existing.setUpdatedAt(Instant.EPOCH);
        when(repo.findById(SNOWFLAKE)).thenReturn(Optional.of(existing));

        service.deduct(SNOWFLAKE, 150L);

        assertThat(existing.getBalance()).isEqualTo(850L);
        assertThat(existing.getUpdatedAt()).isEqualTo(clock.instant());
        verify(repo).save(existing);
    }

    @Test
    @DisplayName("credit adds the amount and updates the timestamp")
    void credit_addsAndTouchesUpdatedAt() {
        UserBalance existing = new UserBalance();
        existing.setSnowflake(SNOWFLAKE);
        existing.setBalance(500L);
        existing.setUpdatedAt(Instant.EPOCH);
        when(repo.findById(SNOWFLAKE)).thenReturn(Optional.of(existing));

        service.credit(SNOWFLAKE, 75L);

        assertThat(existing.getBalance()).isEqualTo(575L);
        assertThat(existing.getUpdatedAt()).isEqualTo(clock.instant());
        verify(repo).save(existing);
    }

    @Test
    @DisplayName("deduct auto-creates the user with the default balance before subtracting")
    void deduct_autoCreatesUser() {
        when(repo.findById(SNOWFLAKE)).thenReturn(Optional.empty());

        service.deduct(SNOWFLAKE, 100L);

        ArgumentCaptor<UserBalance> captor = ArgumentCaptor.forClass(UserBalance.class);
        verify(repo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        UserBalance last = captor.getValue();
        assertThat(last.getBalance()).isEqualTo(config.getDefaultBalance() - 100L);
    }
}
