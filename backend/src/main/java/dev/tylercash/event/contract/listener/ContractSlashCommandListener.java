package dev.tylercash.event.contract.listener;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

/**
 * Handles /contract and /balance slash commands. Implemented in Task 14.
 */
public interface ContractSlashCommandListener {
    void handleSlashCommand(SlashCommandInteractionEvent event);
}
