package dev.tylercash.event.discord;

import lombok.Data;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.intent.Intent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "dev.tylercash.discord")
@Configuration
@Data
public class DiscordConfiguration {
    public static final String EVENT_CATEGORY = "outings";
    public static final String EVENT_ARCHIVE_CATEGORY = EVENT_CATEGORY + "-archive";
    public static final String ACCEPTED_EMOJI = "✅";
    public static final String DECLINED_EMOJI = "❌";
    public static final String MAYBE_EMOJI = "❔";
    private String token;
    private long guildId;
    private long timeout;
    private String seperatorChannel = "";

    @Bean
    public DiscordApi discordClient() {
        return new DiscordApiBuilder()
                .setToken(getToken())
                .addIntents(Intent.MESSAGE_CONTENT)
                .addIntents(Intent.GUILD_MEMBERS)
                .login()
                .join();
    }

}
