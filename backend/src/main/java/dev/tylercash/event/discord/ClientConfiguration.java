package dev.tylercash.event.discord;

import dev.tylercash.event.discord.listener.ButtonInteractionListener;
import dev.tylercash.event.discord.listener.ModalInteractionListener;
import dev.tylercash.event.discord.listener.SlashCommandListener;
import java.util.EnumSet;
import lombok.AllArgsConstructor;
import lombok.Data;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@AllArgsConstructor
public class ClientConfiguration {
    private final ButtonInteractionListener buttonInteractionListener;
    private final ModalInteractionListener modalInteractionListener;
    private final SlashCommandListener slashCommandListener;
    private final DiscordConfiguration discordConfiguration;

    @Bean
    public JDA jda() throws InterruptedException {
        JDA jda = JDABuilder.createDefault(discordConfiguration.getToken())
                .addEventListeners(buttonInteractionListener)
                .addEventListeners(modalInteractionListener)
                .addEventListeners(slashCommandListener)
                .enableIntents(EnumSet.allOf(GatewayIntent.class))
                .build()
                .awaitReady();

        jda.getGuildById(discordConfiguration.getGuildId())
                .updateCommands()
                .addCommands(
                        Commands.slash("balance", "Check your peep coin balance"),
                        Commands.slash("contract", "Prediction contract commands")
                                .addSubcommands(
                                        new SubcommandData("create", "Create a new prediction contract")
                                                .addOption(OptionType.STRING, "title", "The prediction question", true)
                                                .addOption(
                                                        OptionType.STRING,
                                                        "outcome_1",
                                                        "First outcome (blank = YES)",
                                                        false)
                                                .addOption(
                                                        OptionType.STRING,
                                                        "outcome_2",
                                                        "Second outcome (blank = NO)",
                                                        false)
                                                .addOption(OptionType.STRING, "outcome_3", "Third outcome", false)
                                                .addOption(OptionType.STRING, "outcome_4", "Fourth outcome", false)
                                                .addOption(OptionType.STRING, "outcome_5", "Fifth outcome", false),
                                        new SubcommandData("trade", "Trade on a prediction contract")
                                                .addOption(OptionType.STRING, "contract", "Contract name", true, true)
                                                .addOption(OptionType.STRING, "outcome", "Outcome", true, true)
                                                .addOption(
                                                        OptionType.STRING, "amount", "Amount of coins to spend", true),
                                        new SubcommandData("resolve", "Resolve a prediction contract")
                                                .addOption(OptionType.STRING, "contract", "Contract name", true, true)
                                                .addOption(OptionType.STRING, "outcome", "Winning outcome", true, true),
                                        new SubcommandData("cancel", "Cancel a prediction contract")
                                                .addOption(OptionType.STRING, "contract", "Contract name", true, true),
                                        new SubcommandData("list", "List open prediction contracts")))
                .queue();

        return jda;
    }
}
