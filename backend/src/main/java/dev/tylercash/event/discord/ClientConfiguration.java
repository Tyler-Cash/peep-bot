package dev.tylercash.event.discord;

import dev.tylercash.event.discord.listener.ButtonInteractionListener;
import dev.tylercash.event.discord.listener.ModalInteractionListener;
import dev.tylercash.event.discord.listener.MaybeConfirmationReactionListener;
import lombok.AllArgsConstructor;
import lombok.Data;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;

@Data
@Configuration
@AllArgsConstructor
public class ClientConfiguration {
    private final ButtonInteractionListener buttonInteractionListener;
    private final ModalInteractionListener modalInteractionListener;
    private final MaybeConfirmationReactionListener maybeConfirmationReactionListener;
    private final DiscordConfiguration discordConfiguration;


    @Bean
    public JDA jda() throws InterruptedException {
        return JDABuilder.createDefault(discordConfiguration.getToken())
                .addEventListeners(buttonInteractionListener)
                .addEventListeners(modalInteractionListener)
                .addEventListeners(maybeConfirmationReactionListener)
                .enableIntents(EnumSet.allOf(GatewayIntent.class))
                .build()
                .awaitReady();
    }
}
