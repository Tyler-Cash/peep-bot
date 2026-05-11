package dev.tylercash.event.contract;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tylercash.event.PeepBotApplication;
import dev.tylercash.event.contract.model.Contract;
import dev.tylercash.event.contract.model.ContractOutcome;
import dev.tylercash.event.discord.Guild;
import dev.tylercash.event.discord.GuildRepository;
import dev.tylercash.event.test.SharedPostgres;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end tests for the prediction contract flow against real Discord and PostgreSQL.
 * Requires a valid application-local.yaml with Discord credentials, and the bot must
 * already be joined to at least one guild (so a `guild` row exists in the local DB).
 *
 * Run explicitly with: ./gradlew e2eTest
 */
@SpringBootTest(classes = PeepBotApplication.class)
@ActiveProfiles("local")
@Tag("e2e")
class ContractServiceE2ETest {

    @Autowired
    private ContractService contractService;

    @Autowired
    private UserBalanceService balanceService;

    @Autowired
    private GuildRepository guildRepository;

    @Autowired
    private JDA jda;

    private long guildId;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.registerProperties(registry);
    }

    @BeforeEach
    void resolveLocalGuildId() {
        Guild guild = guildRepository.findAllByActiveTrue().stream().findFirst().orElse(null);
        assumeTrue(
                guild != null,
                "No active guild row in local DB — start the bot once so GuildJoinEvent seeds one before running e2eTest");
        guildId = guild.getGuildId();
    }

    @Test
    void createTradeThenResolve() throws InterruptedException {
        String creator = "e2e-creator";
        String trader = "e2e-trader";

        balanceService.credit(creator, 500L);
        balanceService.credit(trader, 500L);

        Contract contract =
                contractService.createContract(guildId, creator, "E2E Test Contract", null, List.of("YES", "NO"));

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

        Contract contract = contractService.createContract(
                guildId, creator, "E2E Cancel Test Contract", null, List.of("YES", "NO"));

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
