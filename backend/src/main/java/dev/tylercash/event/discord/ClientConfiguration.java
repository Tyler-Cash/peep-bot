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
import okhttp3.OkHttpClient;
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
    private final DiscordOkHttpObservationInterceptor httpObservationInterceptor;

    @Bean
    public JDA jda() throws InterruptedException {
        // GUILD_MEMBERS: GuildLifecycleListener#onGuildMemberRemove. GUILD_MESSAGES + MESSAGE_CONTENT:
        // MessageReceivedListener reads Message#getAttachments() for the Immich auto-upload, gated behind
        // MESSAGE_CONTENT (privileged — must also be enabled in the Discord developer portal). Interaction
        // listeners (Button/Modal/Slash) require no intents.
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(httpObservationInterceptor)
                .build();
        return JDABuilder.createDefault(discordConfiguration.getToken())
                .setHttpClient(httpClient)
                .addEventListeners(buttonInteractionListener)
                .addEventListeners(guildLifecycleListener)
                .addEventListeners(modalInteractionListener)
                .addEventListeners(slashCommandListener)
                .addEventListeners(messageReceivedListener)
                .enableIntents(EnumSet.of(
                        GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                .build()
                .awaitReady();
    }
}
