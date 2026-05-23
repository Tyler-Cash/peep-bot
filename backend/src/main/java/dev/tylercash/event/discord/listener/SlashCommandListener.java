package dev.tylercash.event.discord.listener;

import dev.tylercash.event.contract.listener.ContractSlashCommandListener;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class SlashCommandListener extends ListenerAdapter {
    private final ObjectProvider<ContractSlashCommandListener> contractListenerProvider;
    private final ObservationRegistry observationRegistry;

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String name = event.getName();
        if ("contract".equals(name) || "balance".equals(name)) {
            // Synchronous handler — JdaObservations.queue calls inside capture this as
            // their parent at submit time, so per-reply HTTP spans share a single
            // discord.slash-command root in the trace waterfall.
            Observation.createNotStarted("discord.slash-command", observationRegistry)
                    .lowCardinalityKeyValue("command.name", name)
                    .lowCardinalityKeyValue(
                            "command.subcommand", event.getSubcommandName() == null ? "" : event.getSubcommandName())
                    .observe(() -> contractListenerProvider.getObject().handleSlashCommand(event));
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if ("contract".equals(event.getName())) {
            Observation.createNotStarted("discord.auto-complete", observationRegistry)
                    .lowCardinalityKeyValue("command.name", event.getName())
                    .observe(() -> contractListenerProvider.getObject().handleAutoComplete(event));
        }
    }
}
