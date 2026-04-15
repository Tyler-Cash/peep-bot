package dev.tylercash.event.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tylercash.event.contract.model.*;
import dev.tylercash.event.contract.repository.*;
import dev.tylercash.event.discord.DiscordAuthService;
import dev.tylercash.event.discord.DiscordChannelService;
import dev.tylercash.event.discord.DiscordConfiguration;
import dev.tylercash.event.discord.DiscordMessageService;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.util.*;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@AllArgsConstructor
public class ContractService {
    private final ContractRepository contractRepo;
    private final ContractOutcomeRepository outcomeRepo;
    private final ContractTradeRepository tradeRepo;
    private final UserBalanceService balanceService;
    private final LmsrService lmsr;
    private final ContractGraphService graphService;
    private final ContractPinnedMessageService pinnedMessageService;
    private final DiscordChannelService channelService;
    private final DiscordMessageService messageService;
    private final DiscordAuthService authService;
    private final ContractConfiguration config;
    private final DiscordConfiguration discordConfig;
    private final JDA jda;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @CacheEvict(value = "openContracts", allEntries = true)
    @Transactional
    public Contract createContract(
            String creatorSnowflake, String title, String description, List<String> outcomeLabels) {

        if (outcomeLabels.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least 2 outcomes required");
        }

        long houseShares = config.getHouseInitialShares();

        Contract contract = new Contract();
        contract.setTitle(title);
        contract.setDescription(description);
        contract.setCreatorSnowflake(creatorSnowflake);
        contract.setSeedAmount(0L);
        contract.setBParameter(LmsrService.computeB(houseShares, outcomeLabels.size()));
        contract.setState(ContractState.CREATED);
        contract.setServerId(discordConfig.getGuildId());
        contract.setCreatedAt(clock.instant());
        contract = contractRepo.save(contract);

        List<ContractOutcome> outcomes = new ArrayList<>();
        for (String label : outcomeLabels) {
            ContractOutcome outcome = new ContractOutcome();
            outcome.setContract(contract);
            outcome.setLabel(label);
            outcome.setSharesOutstanding(houseShares);
            outcomes.add(outcomeRepo.save(outcome));
        }
        contract.setOutcomes(outcomes);
        contractRepo.save(contract);

        initChannel(contract);
        return contract;
    }

    private void initChannel(Contract contract) {
        Category category = channelService.getOrCreateCategory(
                jda.getGuildById(discordConfig.getGuildId()), config.getCategoryName());
        String channelName = slugify(contract.getTitle());
        TextChannel channel = channelService.createTextChannel(category, channelName);
        contract.setChannelId(channel.getIdLong());
        contract.setState(ContractState.INIT_CHANNEL);

        byte[] chart = graphService.renderChart(
                contract.getOutcomes(), List.of(), contract.getCreatedAt(), contract.getBParameter());
        net.dv8tion.jda.api.entities.MessageEmbed embed = pinnedMessageService.buildEmbed(contract);
        net.dv8tion.jda.api.entities.Message msg =
                messageService.sendEmbedWithAttachment(channel, List.of(embed), chart, "chart.png");
        contract.setMessageId(msg.getIdLong());
        contract.setState(ContractState.OPEN);
        contractRepo.save(contract);
    }

    @Transactional
    public long trade(UUID contractId, UUID outcomeId, String snowflake, long coinAmount) {
        Contract contract = getOpenContract(contractId);

        long maxTrade = balanceService.getMaxTrade(snowflake);
        if (coinAmount > maxTrade) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Trade amount exceeds limit of " + maxTrade + " coins");
        }

        ContractOutcome targetOutcome = contract.getOutcomes().stream()
                .filter(o -> o.getId().equals(outcomeId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Outcome not found"));

        double[] q = getSharesArray(contract);
        int idx = getOutcomeIndex(contract, outcomeId);
        double b = contract.getBParameter();

        ObjectNode probBefore = objectMapper.createObjectNode();
        for (ContractOutcome o : contract.getOutcomes()) {
            probBefore.put(o.getId().toString(), lmsr.probability(q, getOutcomeIndex(contract, o.getId()), b));
        }

        double shares = lmsr.sharesToBuyForCost(q, idx, coinAmount, b);
        long actualCost = lmsr.costToBuy(q, idx, shares, b);

        balanceService.deduct(snowflake, actualCost);

        targetOutcome.setSharesOutstanding(targetOutcome.getSharesOutstanding() + shares);
        outcomeRepo.save(targetOutcome);

        ContractTrade trade = new ContractTrade();
        trade.setContractId(contractId);
        trade.setOutcomeId(outcomeId);
        trade.setSnowflake(snowflake);
        trade.setSharesBought(shares);
        trade.setCostPaid(actualCost);
        trade.setProbBefore(probBefore);
        trade.setTradedAt(clock.instant());
        tradeRepo.save(trade);

        pinnedMessageService.refresh(contract);

        double[] qAfter = getSharesArray(contract);
        double probAfter = lmsr.probability(qAfter, idx, b) * 100;
        double probBeforeVal = probBefore.get(targetOutcome.getId().toString()).asDouble() * 100;
        TextChannel channel = channelService.getTextChannel(contract.getChannelId());
        messageService.sendMessage(
                channel,
                String.format(
                        "<@%s> bought %.1f shares of **%s** for **%d %s** \u00B7 %s %.0f%% \u2192 %.0f%%",
                        snowflake,
                        shares,
                        targetOutcome.getLabel(),
                        actualCost,
                        config.getEmoji().getCoin(),
                        targetOutcome.getLabel(),
                        probBeforeVal,
                        probAfter));
        return actualCost;
    }

    @CacheEvict(value = "openContracts", allEntries = true)
    @Transactional
    public void resolveContract(UUID contractId, UUID winningOutcomeId, String resolverSnowflake) {
        Contract contract = getOpenContract(contractId);

        ContractOutcome winner = contract.getOutcomes().stream()
                .filter(o -> o.getId().equals(winningOutcomeId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Outcome not found"));

        List<ContractTrade> allTrades = tradeRepo.findByContractIdOrderByTradedAtAsc(contractId);

        Map<String, Double> winningShares = new HashMap<>();
        Map<String, Long> losingSpend = new LinkedHashMap<>();
        long totalCostPaid = 0;
        for (ContractTrade t : allTrades) {
            totalCostPaid += t.getCostPaid();
            if (t.getOutcomeId().equals(winningOutcomeId)) {
                winningShares.merge(t.getSnowflake(), t.getSharesBought(), Double::sum);
            } else {
                losingSpend.merge(t.getSnowflake(), t.getCostPaid(), Long::sum);
            }
        }
        losingSpend.keySet().removeAll(winningShares.keySet());

        Map<String, Long> payouts = new LinkedHashMap<>();
        long totalPayout = 0;
        for (Map.Entry<String, Double> entry : winningShares.entrySet()) {
            long payout = Math.round(entry.getValue());
            balanceService.credit(entry.getKey(), payout);
            payouts.put(entry.getKey(), payout);
            totalPayout += payout;
        }

        long creatorReturn = Math.max(0, contract.getSeedAmount() + totalCostPaid - totalPayout);
        if (creatorReturn > 0) {
            balanceService.credit(contract.getCreatorSnowflake(), creatorReturn);
        }

        contract.setWinningOutcomeId(winningOutcomeId);
        contract.setState(ContractState.RESOLVED);
        contractRepo.save(contract);

        pinnedMessageService.refresh(contract);

        StringBuilder msg = new StringBuilder();
        msg.append(config.getEmoji().getSuccess())
                .append(" **Prediction contract resolved!** Winning outcome: **")
                .append(winner.getLabel())
                .append("**\n");
        if (!payouts.isEmpty()) {
            msg.append("\n\uD83C\uDF89 **Winners**\n");
            payouts.forEach((snowflake, payout) -> msg.append("\u2022 <@")
                    .append(snowflake)
                    .append("> \u2014 +")
                    .append(payout)
                    .append(" ")
                    .append(config.getEmoji().getCoin())
                    .append("\n"));
        }
        if (!losingSpend.isEmpty()) {
            msg.append("\n").append(config.getEmoji().getPoor()).append(" **Losers**\n");
            losingSpend.forEach(
                    (snowflake, spent) -> msg.append(config.getEmoji().getPoor())
                            .append(" <@")
                            .append(snowflake)
                            .append("> \u2014 -")
                            .append(spent)
                            .append(" ")
                            .append(config.getEmoji().getCoin())
                            .append("\n"));
        }

        TextChannel channel = channelService.getTextChannel(contract.getChannelId());
        messageService.sendMessage(channel, msg.toString());
    }

    @CacheEvict(value = "openContracts", allEntries = true)
    @Transactional
    public void cancelContract(UUID contractId, String resolverSnowflake) {
        Contract contract = getOpenContract(contractId);

        List<ContractTrade> allTrades = tradeRepo.findByContractIdOrderByTradedAtAsc(contractId);
        for (ContractTrade trade : allTrades) {
            balanceService.credit(trade.getSnowflake(), trade.getCostPaid());
        }
        balanceService.credit(contract.getCreatorSnowflake(), contract.getSeedAmount());

        contract.setState(ContractState.CANCELLED);
        contractRepo.save(contract);

        TextChannel channel = channelService.getTextChannel(contract.getChannelId());
        messageService.sendMessage(channel, "\u274C **Prediction contract cancelled.** All trades refunded.");
    }

    @Cacheable("openContracts")
    public List<Contract> listOpenContracts() {
        return contractRepo.findByStateIn(List.of(ContractState.OPEN));
    }

    public Contract findOpenContractByTitle(String title) {
        return contractRepo
                .findFirstByStateInAndTitleIgnoreCase(List.of(ContractState.OPEN), title)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found: " + title));
    }

    public List<ContractSummary> searchOpenContractNames(String query) {
        return contractRepo.searchSummariesByStateInAndTitleContaining(List.of(ContractState.OPEN), query);
    }

    public List<ContractOutcome> getOpenContractOutcomes(UUID contractId) {
        return getOpenContract(contractId).getOutcomes();
    }

    private Contract getOpenContract(UUID id) {
        Contract contract = contractRepo
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found"));
        if (contract.getState() != ContractState.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Contract is not open");
        }
        return contract;
    }

    private double[] getSharesArray(Contract contract) {
        return contract.getOutcomes().stream()
                .mapToDouble(ContractOutcome::getSharesOutstanding)
                .toArray();
    }

    private int getOutcomeIndex(Contract contract, UUID outcomeId) {
        for (int i = 0; i < contract.getOutcomes().size(); i++) {
            if (contract.getOutcomes().get(i).getId().equals(outcomeId)) return i;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Outcome not found");
    }

    private String slugify(String title) {
        String slug = title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-");
        return slug.substring(0, Math.min(slug.length(), 90));
    }
}
