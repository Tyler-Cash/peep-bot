package dev.tylercash.event.contract;

import dev.tylercash.event.contract.model.Contract;
import dev.tylercash.event.contract.model.ContractOutcome;
import dev.tylercash.event.contract.model.ContractState;
import dev.tylercash.event.contract.model.ContractTrade;
import dev.tylercash.event.contract.repository.ContractTradeRepository;
import dev.tylercash.event.discord.DiscordMessageService;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@AllArgsConstructor
public class ContractPinnedMessageService {
    private final ContractTradeRepository tradeRepo;
    private final ContractGraphService graphService;
    private final DiscordMessageService messageService;
    private final LmsrService lmsr;
    private final ContractConfiguration config;

    @Async
    public void refresh(Contract contract) {
        try {
            List<ContractTrade> trades = tradeRepo.findByContractIdOrderByTradedAtAsc(contract.getId());
            byte[] chart = graphService.renderChart(
                    contract.getOutcomes(), trades, contract.getCreatedAt(), contract.getBParameter());
            MessageEmbed embed = buildEmbed(contract);
            messageService.editEmbedWithAttachment(
                    contract.getChannelId(), contract.getMessageId(), List.of(embed), chart, "chart.png");
        } catch (Exception e) {
            log.error("Failed to refresh pinned message for contract {}", contract.getId(), e);
        }
    }

    public MessageEmbed buildEmbed(Contract contract) {
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("\uD83D\uDCC8 " + contract.getTitle())
                .setColor(0xF0A732)
                .setImage("attachment://chart.png");

        StringBuilder desc = new StringBuilder();
        if (contract.getDescription() != null) {
            desc.append(contract.getDescription()).append("\n");
        }
        desc.append("\n**Outcomes:**\n");

        double[] q = contract.getOutcomes().stream()
                .mapToDouble(ContractOutcome::getSharesOutstanding)
                .toArray();
        double b = contract.getBParameter();
        for (int i = 0; i < contract.getOutcomes().size(); i++) {
            ContractOutcome o = contract.getOutcomes().get(i);
            double prob = lmsr.probability(q, i, b);
            desc.append(String.format(
                    "\u2022 **%s** \u2014 %.1f%% \u00B7 %.2f \uD83E\uDE99/share\n", o.getLabel(), prob * 100, prob));
        }
        desc.append("\nUse `/contract trade` to participate \u00B7 `/balance` to check coins");

        eb.setDescription(desc.toString());

        if (contract.getState() == ContractState.RESOLVED) {
            contract.getOutcomes().stream()
                    .filter(o -> o.getId().equals(contract.getWinningOutcomeId()))
                    .findFirst()
                    .ifPresent(w -> eb.setFooter(config.getEmoji().getSuccess() + " Resolved: " + w.getLabel()));
        }

        return eb.build();
    }
}
