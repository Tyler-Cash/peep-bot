package dev.tylercash.event.contract.listener;

import dev.tylercash.event.contract.ContractConfiguration;
import dev.tylercash.event.contract.ContractService;
import dev.tylercash.event.contract.UserBalanceService;
import dev.tylercash.event.contract.model.Contract;
import dev.tylercash.event.contract.model.ContractOutcome;
import dev.tylercash.event.discord.DiscordAuthService;
import dev.tylercash.event.discord.Feature;
import dev.tylercash.event.discord.FeatureFlagService;
import dev.tylercash.event.discord.JdaObservations;
import io.micrometer.observation.ObservationRegistry;
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
    private final FeatureFlagService featureFlagService;
    private final ObservationRegistry observationRegistry;

    @Override
    public void handleSlashCommand(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null
                || !featureFlagService.isEnabled(event.getGuild().getIdLong(), Feature.CONTRACTS)) {
            JdaObservations.queue(
                    event.reply("Prediction contracts are not enabled in this server.")
                            .setEphemeral(true),
                    "discord.reply.queue",
                    observationRegistry);
            return;
        }

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
                default -> JdaObservations.queue(
                        event.reply("Unknown subcommand.").setEphemeral(true),
                        "discord.reply.queue",
                        observationRegistry);
            }
        }
    }

    private void handleBalance(SlashCommandInteractionEvent event) {
        long balance = balanceService.getBalance(event.getUser().getId());
        JdaObservations.queue(
                event.reply("Your balance: **" + balance + " "
                                + contractConfig.getEmoji().getCoin() + " peep coins**")
                        .setEphemeral(true),
                "discord.reply.queue",
                observationRegistry);
    }

    private void handleCreate(SlashCommandInteractionEvent event) {
        JdaObservations.queue(event.deferReply(true), "discord.defer-reply.queue", observationRegistry);
        try {
            String title = event.getOption("title").getAsString();

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
            long guildId = event.getGuild().getIdLong();
            contractService.createContract(guildId, userId, title, null, outcomeLabels);

            String emoji = contractConfig.getEmoji().getSuccess();
            JdaObservations.queue(
                    event.getHook().sendMessage(emoji + " Prediction contract created!"),
                    "discord.hook.send-message.queue",
                    observationRegistry);
        } catch (Exception e) {
            log.warn("Contract creation failed", e);
            JdaObservations.queue(
                    event.getHook().sendMessage("❌ Error: " + e.getMessage()),
                    "discord.hook.send-message.queue",
                    observationRegistry);
        }
    }

    private void handleTrade(SlashCommandInteractionEvent event) {
        String contractIdStr = getOption(event, "contract");
        String outcomeIdStr = getOption(event, "outcome");
        String amountStr = getOption(event, "amount");

        if (contractIdStr == null || outcomeIdStr == null || amountStr == null) {
            JdaObservations.queue(
                    event.reply("Missing required options.").setEphemeral(true),
                    "discord.reply.queue",
                    observationRegistry);
            return;
        }

        JdaObservations.queue(event.deferReply(true), "discord.defer-reply.queue", observationRegistry);
        try {
            Contract contract =
                    contractService.findOpenContractByTitle(event.getGuild().getIdLong(), contractIdStr);
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
            JdaObservations.queue(
                    event.getHook().sendMessage(reply), "discord.hook.send-message.queue", observationRegistry);
        } catch (Exception e) {
            log.warn("Trade failed", e);
            JdaObservations.queue(
                    event.getHook().sendMessage("Whoops, something went wrong. Please try again."),
                    "discord.hook.send-message.queue",
                    observationRegistry);
        }
    }

    private void handleResolve(SlashCommandInteractionEvent event) {
        if (!authService.hasRole(
                event.getGuild().getIdLong(), event.getUser().getIdLong(), contractConfig.getResolverRole())) {
            JdaObservations.queue(
                    event.reply("You don't have the **" + contractConfig.getResolverRole() + "** role.")
                            .setEphemeral(true),
                    "discord.reply.queue",
                    observationRegistry);
            return;
        }

        String contractIdStr = getOption(event, "contract");
        String outcomeIdStr = getOption(event, "outcome");
        if (contractIdStr == null || outcomeIdStr == null) {
            JdaObservations.queue(
                    event.reply("Missing required options.").setEphemeral(true),
                    "discord.reply.queue",
                    observationRegistry);
            return;
        }

        JdaObservations.queue(event.deferReply(true), "discord.defer-reply.queue", observationRegistry);
        try {
            Contract contract =
                    contractService.findOpenContractByTitle(event.getGuild().getIdLong(), contractIdStr);
            UUID outcomeId = contract.getOutcomes().stream()
                    .filter(o -> o.getLabel().equalsIgnoreCase(outcomeIdStr))
                    .map(ContractOutcome::getId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Outcome not found: " + outcomeIdStr));
            contractService.resolveContract(
                    contract.getId(), outcomeId, event.getUser().getId());
            JdaObservations.queue(
                    event.getHook().sendMessage(contractConfig.getEmoji().getSuccess() + " Contract resolved!"),
                    "discord.hook.send-message.queue",
                    observationRegistry);
        } catch (Exception e) {
            log.warn("Resolve failed", e);
            JdaObservations.queue(
                    event.getHook().sendMessage("Whoops, something went wrong. Please try again."),
                    "discord.hook.send-message.queue",
                    observationRegistry);
        }
    }

    private void handleCancel(SlashCommandInteractionEvent event) {
        if (!authService.hasRole(
                event.getGuild().getIdLong(), event.getUser().getIdLong(), contractConfig.getResolverRole())) {
            JdaObservations.queue(
                    event.reply("You don't have the **" + contractConfig.getResolverRole() + "** role.")
                            .setEphemeral(true),
                    "discord.reply.queue",
                    observationRegistry);
            return;
        }

        String contractIdStr = getOption(event, "contract");
        if (contractIdStr == null) {
            JdaObservations.queue(
                    event.reply("Missing contract ID.").setEphemeral(true), "discord.reply.queue", observationRegistry);
            return;
        }

        JdaObservations.queue(event.deferReply(true), "discord.defer-reply.queue", observationRegistry);
        try {
            Contract contract =
                    contractService.findOpenContractByTitle(event.getGuild().getIdLong(), contractIdStr);
            contractService.cancelContract(contract.getId(), event.getUser().getId());
            JdaObservations.queue(
                    event.getHook().sendMessage("❌ Contract cancelled. All trades refunded."),
                    "discord.hook.send-message.queue",
                    observationRegistry);
        } catch (Exception e) {
            log.warn("Cancel failed", e);
            JdaObservations.queue(
                    event.getHook().sendMessage("Whoops, something went wrong. Please try again."),
                    "discord.hook.send-message.queue",
                    observationRegistry);
        }
    }

    private void handleList(SlashCommandInteractionEvent event) {
        List<Contract> open = contractService.listOpenContracts(event.getGuild().getIdLong());
        if (open.isEmpty()) {
            JdaObservations.queue(
                    event.reply("No open prediction contracts.").setEphemeral(true),
                    "discord.reply.queue",
                    observationRegistry);
            return;
        }
        StringBuilder sb = new StringBuilder("**Open prediction contracts:**\n");
        for (Contract c : open) {
            sb.append("• **")
                    .append(c.getTitle())
                    .append("** — ID: `")
                    .append(c.getId())
                    .append("`\n");
            for (ContractOutcome o : c.getOutcomes()) {
                sb.append("  · ")
                        .append(o.getLabel())
                        .append(" — outcome ID: `")
                        .append(o.getId())
                        .append("`\n");
            }
        }
        JdaObservations.queue(
                event.reply(sb.toString()).setEphemeral(true), "discord.reply.queue", observationRegistry);
    }

    @Override
    public void handleAutoComplete(CommandAutoCompleteInteractionEvent event) {
        if (event.getGuild() == null
                || !featureFlagService.isEnabled(event.getGuild().getIdLong(), Feature.CONTRACTS)) {
            JdaObservations.queue(event.replyChoices(List.of()), "discord.reply-choices.queue", observationRegistry);
            return;
        }
        long guildId = event.getGuild().getIdLong();
        AutoCompleteQuery focused = event.getFocusedOption();
        String typed = focused.getValue().toLowerCase();

        if ("contract".equals(focused.getName())) {
            List<Choice> choices = contractService.searchOpenContractNames(guildId, typed).stream()
                    .limit(25)
                    .map(s -> new Choice(s.title(), s.title()))
                    .toList();
            JdaObservations.queue(event.replyChoices(choices), "discord.reply-choices.queue", observationRegistry);
        } else if ("outcome".equals(focused.getName())) {
            String contractTitle = event.getOption("contract") != null
                    ? event.getOption("contract").getAsString()
                    : null;
            if (contractTitle == null || contractTitle.isBlank()) {
                JdaObservations.queue(
                        event.replyChoices(List.of()), "discord.reply-choices.queue", observationRegistry);
                return;
            }
            try {
                List<Choice> choices =
                        contractService.findOpenContractByTitle(guildId, contractTitle).getOutcomes().stream()
                                .filter(o -> o.getLabel().toLowerCase().contains(typed))
                                .map(o -> new Choice(o.getLabel(), o.getLabel()))
                                .toList();
                JdaObservations.queue(event.replyChoices(choices), "discord.reply-choices.queue", observationRegistry);
            } catch (Exception e) {
                JdaObservations.queue(
                        event.replyChoices(List.of()), "discord.reply-choices.queue", observationRegistry);
            }
        } else {
            JdaObservations.queue(event.replyChoices(List.of()), "discord.reply-choices.queue", observationRegistry);
        }
    }

    private String getOption(SlashCommandInteractionEvent event, String name) {
        OptionMapping opt = event.getOption(name);
        return opt != null ? opt.getAsString() : null;
    }
}
