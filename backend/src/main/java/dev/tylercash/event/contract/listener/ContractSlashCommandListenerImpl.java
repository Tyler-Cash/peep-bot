package dev.tylercash.event.contract.listener;

import dev.tylercash.event.contract.ContractConfiguration;
import dev.tylercash.event.contract.ContractService;
import dev.tylercash.event.contract.UserBalanceService;
import dev.tylercash.event.contract.model.Contract;
import dev.tylercash.event.contract.model.ContractOutcome;
import dev.tylercash.event.discord.DiscordAuthService;
import dev.tylercash.event.discord.DiscordConfiguration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.AutoCompleteQuery;
import net.dv8tion.jda.api.interactions.commands.Command.Choice;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class ContractSlashCommandListenerImpl implements ContractSlashCommandListener {
    private final ContractService contractService;
    private final UserBalanceService balanceService;
    private final DiscordAuthService authService;
    private final ContractConfiguration contractConfig;
    private final DiscordConfiguration discordConfig;

    @Override
    public void handleSlashCommand(SlashCommandInteractionEvent event) {
        String name = event.getName();
        String sub = event.getSubcommandName();

        if ("balance".equals(name)) {
            handleBalance(event);
            return;
        }

        if ("contract".equals(name)) {
            switch (sub != null ? sub : "") {
                case "create" -> handleCreate(event);
                case "trade" -> handleTrade(event);
                case "resolve" -> handleResolve(event);
                case "cancel" -> handleCancel(event);
                case "list" -> handleList(event);
                default -> event.reply("Unknown subcommand.").setEphemeral(true).queue();
            }
        }
    }

    private void handleBalance(SlashCommandInteractionEvent event) {
        long balance = balanceService.getBalance(event.getUser().getId());
        event.reply("Your balance: **" + balance + " " + contractConfig.getEmoji().getCoin() + " peep coins**")
                .setEphemeral(true)
                .queue();
    }

    private void handleCreate(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();
        try {
            String title = event.getOption("title").getAsString();
            long seedAmount = event.getOption("seed").getAsLong();

            List<String> outcomeLabels = Stream.of("outcome_1", "outcome_2", "outcome_3", "outcome_4", "outcome_5")
                    .map(event::getOption)
                    .filter(o -> o != null && !o.getAsString().isBlank())
                    .map(o -> o.getAsString().trim())
                    .toList();
            if (outcomeLabels.isEmpty()) {
                outcomeLabels = List.of("YES", "NO");
            } else if (outcomeLabels.size() == 1) {
                outcomeLabels = List.of(outcomeLabels.get(0), "NO");
            }

            String userId = event.getUser().getId();
            contractService.createContract(userId, title, null, outcomeLabels, seedAmount);

            String emoji = contractConfig.getEmoji().getSuccess();
            event.getHook().sendMessage(emoji + " Prediction contract created!").queue();
        } catch (Exception e) {
            log.warn("Contract creation failed", e);
            event.getHook().sendMessage("\u274C Error: " + e.getMessage()).queue();
        }
    }

    private void handleTrade(SlashCommandInteractionEvent event) {
        String contractIdStr = getOption(event, "contract");
        String outcomeIdStr = getOption(event, "outcome");
        String amountStr = getOption(event, "amount");

        if (contractIdStr == null || outcomeIdStr == null || amountStr == null) {
            event.reply("Missing required options.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();
        try {
            Contract contract = contractService.findOpenContractByTitle(contractIdStr);
            UUID outcomeId = contract.getOutcomes().stream()
                    .filter(o -> o.getLabel().equalsIgnoreCase(outcomeIdStr))
                    .map(ContractOutcome::getId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Outcome not found: " + outcomeIdStr));
            long amount = Long.parseLong(amountStr);
            if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
            long actualCost = contractService.trade(
                    contract.getId(), outcomeId, event.getUser().getId(), amount);
            String emoji = contractConfig.getEmoji().getSuccess();
            String reply = actualCost < amount
                    ? String.format(
                            "Trade placed! %s · Spent **%d** of **%d** %s (rounding to nearest coin)",
                            emoji, actualCost, amount, contractConfig.getEmoji().getCoin())
                    : "Trade placed! " + emoji;
            event.getHook().sendMessage(reply).queue();
        } catch (Exception e) {
            log.warn("Trade failed", e);
            event.getHook()
                    .sendMessage("Whoops, something went wrong. Please try again.")
                    .queue();
        }
    }

    private void handleResolve(SlashCommandInteractionEvent event) {
        if (!authService.hasRole(
                discordConfig.getGuildId(), event.getUser().getIdLong(), contractConfig.getResolverRole())) {
            event.reply("You don't have the **" + contractConfig.getResolverRole() + "** role.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String contractIdStr = getOption(event, "contract");
        String outcomeIdStr = getOption(event, "outcome");
        if (contractIdStr == null || outcomeIdStr == null) {
            event.reply("Missing required options.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();
        try {
            Contract contract = contractService.findOpenContractByTitle(contractIdStr);
            UUID outcomeId = contract.getOutcomes().stream()
                    .filter(o -> o.getLabel().equalsIgnoreCase(outcomeIdStr))
                    .map(ContractOutcome::getId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Outcome not found: " + outcomeIdStr));
            contractService.resolveContract(
                    contract.getId(), outcomeId, event.getUser().getId());
            event.getHook()
                    .sendMessage(contractConfig.getEmoji().getSuccess() + " Contract resolved!")
                    .queue();
        } catch (Exception e) {
            log.warn("Resolve failed", e);
            event.getHook()
                    .sendMessage("Whoops, something went wrong. Please try again.")
                    .queue();
        }
    }

    private void handleCancel(SlashCommandInteractionEvent event) {
        if (!authService.hasRole(
                discordConfig.getGuildId(), event.getUser().getIdLong(), contractConfig.getResolverRole())) {
            event.reply("You don't have the **" + contractConfig.getResolverRole() + "** role.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String contractIdStr = getOption(event, "contract");
        if (contractIdStr == null) {
            event.reply("Missing contract ID.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();
        try {
            Contract contract = contractService.findOpenContractByTitle(contractIdStr);
            contractService.cancelContract(contract.getId(), event.getUser().getId());
            event.getHook()
                    .sendMessage("\u274C Contract cancelled. All trades refunded.")
                    .queue();
        } catch (Exception e) {
            log.warn("Cancel failed", e);
            event.getHook()
                    .sendMessage("Whoops, something went wrong. Please try again.")
                    .queue();
        }
    }

    private void handleList(SlashCommandInteractionEvent event) {
        List<Contract> open = contractService.listOpenContracts();
        if (open.isEmpty()) {
            event.reply("No open prediction contracts.").setEphemeral(true).queue();
            return;
        }
        StringBuilder sb = new StringBuilder("**Open prediction contracts:**\n");
        for (Contract c : open) {
            sb.append("\u2022 **")
                    .append(c.getTitle())
                    .append("** \u2014 ID: `")
                    .append(c.getId())
                    .append("`\n");
            for (ContractOutcome o : c.getOutcomes()) {
                sb.append("  \u00B7 ")
                        .append(o.getLabel())
                        .append(" \u2014 outcome ID: `")
                        .append(o.getId())
                        .append("`\n");
            }
        }
        event.reply(sb.toString()).setEphemeral(true).queue();
    }

    @Override
    public void handleAutoComplete(CommandAutoCompleteInteractionEvent event) {
        AutoCompleteQuery focused = event.getFocusedOption();
        String typed = focused.getValue().toLowerCase();

        if ("contract".equals(focused.getName())) {
            List<Choice> choices = contractService.searchOpenContractNames(typed).stream()
                    .limit(25)
                    .map(s -> new Choice(s.title(), s.title()))
                    .toList();
            event.replyChoices(choices).queue();
        } else if ("outcome".equals(focused.getName())) {
            String contractTitle = event.getOption("contract") != null
                    ? event.getOption("contract").getAsString()
                    : null;
            if (contractTitle == null || contractTitle.isBlank()) {
                event.replyChoices(List.of()).queue();
                return;
            }
            try {
                List<Choice> choices = contractService.findOpenContractByTitle(contractTitle).getOutcomes().stream()
                        .filter(o -> o.getLabel().toLowerCase().contains(typed))
                        .map(o -> new Choice(o.getLabel(), o.getLabel()))
                        .toList();
                event.replyChoices(choices).queue();
            } catch (Exception e) {
                event.replyChoices(List.of()).queue();
            }
        } else {
            event.replyChoices(List.of()).queue();
        }
    }

    private String getOption(SlashCommandInteractionEvent event, String name) {
        OptionMapping opt = event.getOption(name);
        return opt != null ? opt.getAsString() : null;
    }
}
