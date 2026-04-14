package dev.tylercash.event.discord.listener;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SlashCommandListener extends ListenerAdapter {
    private final ObjectProvider<dev.tylercash.event.contract.listener.ContractSlashCommandListener>
            contractListenerProvider;

    public SlashCommandListener(
            ObjectProvider<dev.tylercash.event.contract.listener.ContractSlashCommandListener>
                    contractListenerProvider) {
        this.contractListenerProvider = contractListenerProvider;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String name = event.getName();
        if ("contract".equals(name) || "balance".equals(name)) {
            contractListenerProvider.getObject().handleSlashCommand(event);
        }
    }
}
