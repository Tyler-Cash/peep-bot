package dev.tylercash.event.contract;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.contract.model.Contract;
import dev.tylercash.event.contract.model.ContractOutcome;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end tests for the prediction contract flow against real Discord and PostgreSQL.
 * Requires a valid application-local.yaml with Discord credentials.
 *
 * Run explicitly with: ./gradlew e2eTest
 */
@SpringBootTest(classes = PeepBotApplication.class)
@Testcontainers
@ActiveProfiles("local")
@Tag("e2e")
class ContractServiceE2ETest {

    @Container
    public static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:0.8.0-pg17");

    @Autowired
    private ContractService contractService;

    @Autowired
    private UserBalanceService balanceService;

    @Autowired
    private JDA jda;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void createTradeThenResolve() throws InterruptedException {
        String creator = "e2e-creator";
        String trader = "e2e-trader";

        balanceService.credit(creator, 500L);
        balanceService.credit(trader, 500L);

        Contract contract = contractService.createContract(creator, "E2E Test Contract", null, List.of("YES", "NO"));

        ContractOutcome yes = contract.getOutcomes().stream()
                .filter(o -> o.getLabel().equals("YES"))
                .findFirst()
                .orElseThrow();

        contractService.trade(contract.getId(), yes.getId(), trader, 50L);
        contractService.resolveContract(contract.getId(), yes.getId(), creator);

        cleanup(contract);
    }

    @Test
    void createTradeThenCancel() throws InterruptedException {
        String creator = "e2e-creator-2";
        String trader = "e2e-trader-2";

        balanceService.credit(creator, 500L);
        balanceService.credit(trader, 500L);

        Contract contract =
                contractService.createContract(creator, "E2E Cancel Test Contract", null, List.of("YES", "NO"));

        ContractOutcome no = contract.getOutcomes().stream()
                .filter(o -> o.getLabel().equals("NO"))
                .findFirst()
                .orElseThrow();

        contractService.trade(contract.getId(), no.getId(), trader, 30L);
        contractService.cancelContract(contract.getId(), creator);

        cleanup(contract);
    }

    private void cleanup(Contract contract) throws InterruptedException {
        Thread.sleep(2000);
        if (contract.getChannelId() != null) {
            TextChannel channel = jda.getTextChannelById(contract.getChannelId());
            if (channel != null) {
                channel.delete().queue();
            }
        }
    }
}
