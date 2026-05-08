package dev.tylercash.event.discord;

import dev.tylercash.event.discord.listener.ButtonInteractionListener;
import dev.tylercash.event.discord.listener.GuildLifecycleListener;
import dev.tylercash.event.discord.listener.MessageReceivedListener;
import dev.tylercash.event.discord.listener.ModalInteractionListener;
import dev.tylercash.event.discord.listener.SlashCommandListener;
import java.util.EnumSet;
import lombok.AllArgsConstructor;
import lombok.Data;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@AllArgsConstructor
public class ClientConfiguration {
    private final ButtonInteractionListener buttonInteractionListener;
    private final GuildLifecycleListener guildLifecycleListener;
    private final ModalInteractionListener modalInteractionListener;
    private final SlashCommandListener slashCommandListener;
    private final MessageReceivedListener messageReceivedListener;
    private final DiscordConfiguration discordConfiguration;

    @Bean
    public JDA jda() throws InterruptedException {
        return JDABuilder.createDefault(discordConfiguration.getToken())
                .addEventListeners(buttonInteractionListener)
                .addEventListeners(guildLifecycleListener)
                .addEventListeners(modalInteractionListener)
                .addEventListeners(slashCommandListener)
                .addEventListeners(messageReceivedListener)
                .enableIntents(EnumSet.allOf(GatewayIntent.class))
                .build()
                .awaitReady();
    }
}
