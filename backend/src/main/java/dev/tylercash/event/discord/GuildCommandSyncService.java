package dev.tylercash.event.discord;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuildCommandSyncService {
    private final FeatureFlagService featureFlagService;

    public void syncCommands(net.dv8tion.jda.api.entities.Guild jdaGuild) {
        long id = jdaGuild.getIdLong();
        List<CommandData> commands = buildCommandsFor(id);
        log.info("Syncing {} commands to guild '{}' ({})", commands.size(), jdaGuild.getName(), id);
        jdaGuild.updateCommands()
                .addCommands(commands)
                .queue(
                        ok -> log.debug("Synced {} commands for guild {}", commands.size(), id),
                        err -> log.error("Failed to sync commands for guild {}: {}", id, err.getMessage(), err));
    }

    public void clearGlobalCommands(JDA jda) {
        jda.updateCommands()
                .queue(
                        ok -> log.info("Cleared global Discord commands"),
                        err -> log.error("Failed to clear global commands: {}", err.getMessage(), err));
    }

    private List<CommandData> buildCommandsFor(long guildId) {
        List<CommandData> commands = new ArrayList<>();
        if (featureFlagService.isEnabled(guildId, Feature.CONTRACTS)) {
            commands.add(Commands.slash("balance", "Check your peep coin balance"));
            commands.add(Commands.slash("contract", "Prediction contract commands")
                    .addSubcommands(
                            new SubcommandData("create", "Create a new prediction contract")
                                    .addOption(OptionType.STRING, "title", "The prediction question", true)
                                    .addOption(OptionType.STRING, "outcome_1", "First outcome (blank = YES)", false)
                                    .addOption(OptionType.STRING, "outcome_2", "Second outcome (blank = NO)", false)
                                    .addOption(OptionType.STRING, "outcome_3", "Third outcome", false)
                                    .addOption(OptionType.STRING, "outcome_4", "Fourth outcome", false)
                                    .addOption(OptionType.STRING, "outcome_5", "Fifth outcome", false),
                            new SubcommandData("trade", "Trade on a prediction contract")
                                    .addOption(OptionType.STRING, "contract", "Contract name", true, true)
                                    .addOption(OptionType.STRING, "outcome", "Outcome", true, true)
                                    .addOption(OptionType.STRING, "amount", "Amount of coins to spend", true),
                            new SubcommandData("resolve", "Resolve a prediction contract")
                                    .addOption(OptionType.STRING, "contract", "Contract name", true, true)
                                    .addOption(OptionType.STRING, "outcome", "Winning outcome", true, true),
                            new SubcommandData("cancel", "Cancel a prediction contract")
                                    .addOption(OptionType.STRING, "contract", "Contract name", true, true),
                            new SubcommandData("list", "List open prediction contracts")));
        }
        return commands;
    }
}
