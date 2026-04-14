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
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.modals.Modal;
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
        event.reply("Your balance: **" + balance + " \uD83E\uDE99 peep coins**")
                .setEphemeral(true)
                .queue();
    }

    private void handleCreate(SlashCommandInteractionEvent event) {
        TextInput title = TextInput.create("title", TextInputStyle.SHORT)
                .setPlaceholder("Will we hit 100 members by June?")
                .setRequiredRange(5, 200)
                .build();

        TextInput description = TextInput.create("description", TextInputStyle.PARAGRAPH)
                .setPlaceholder("Optional description")
                .setRequired(false)
                .build();

        TextInput outcomes = TextInput.create("outcomes", TextInputStyle.SHORT)
                .setPlaceholder("YES,NO")
                .setRequired(false)
                .build();

        TextInput seed = TextInput.create("seed", TextInputStyle.SHORT)
                .setPlaceholder("500")
                .setRequiredRange(1, 10)
                .build();

        Modal modal = Modal.create("contract_create", "Create Prediction Contract")
                .addComponents(
                        Label.of("Title", title),
                        Label.of("Description (optional)", description),
                        Label.of("Outcomes (comma-separated, blank = YES/NO)", outcomes),
                        Label.of("Seed amount (\uD83E\uDE99 coins you stake)", seed))
                .build();

        event.replyModal(modal).queue();
    }

    private void handleTrade(SlashCommandInteractionEvent event) {
        String contractIdStr = getOption(event, "contract");
        String outcomeIdStr = getOption(event, "outcome");
        String amountStr = getOption(event, "amount");

        if (contractIdStr == null || outcomeIdStr == null || amountStr == null) {
            event.reply("Missing required options.").setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();
        try {
            UUID contractId = UUID.fromString(contractIdStr);
            UUID outcomeId = UUID.fromString(outcomeIdStr);
            long amount = Long.parseLong(amountStr);
            if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
            contractService.trade(contractId, outcomeId, event.getUser().getId(), amount);
            event.getHook().sendMessage("Trade placed! \u2705").queue();
        } catch (Exception e) {
            log.warn("Trade failed", e);
            event.getHook()
                    .sendMessage("Trade failed: " + e.getMessage())
                    .setEphemeral(true)
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

        event.deferReply().queue();
        try {
            contractService.resolveContract(
                    UUID.fromString(contractIdStr),
                    UUID.fromString(outcomeIdStr),
                    event.getUser().getId());
            event.getHook().sendMessage("\u2705 Contract resolved!").queue();
        } catch (Exception e) {
            log.warn("Resolve failed", e);
            event.getHook()
                    .sendMessage("Resolve failed: " + e.getMessage())
                    .setEphemeral(true)
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

        event.deferReply().queue();
        try {
            contractService.cancelContract(
                    UUID.fromString(contractIdStr), event.getUser().getId());
            event.getHook()
                    .sendMessage("\u274C Contract cancelled. All trades refunded.")
                    .queue();
        } catch (Exception e) {
            log.warn("Cancel failed", e);
            event.getHook()
                    .sendMessage("Cancel failed: " + e.getMessage())
                    .setEphemeral(true)
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

    private String getOption(SlashCommandInteractionEvent event, String name) {
        OptionMapping opt = event.getOption(name);
        return opt != null ? opt.getAsString() : null;
    }
}
