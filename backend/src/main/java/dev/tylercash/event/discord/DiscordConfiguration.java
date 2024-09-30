package dev.tylercash.event.discord;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.discord")
public class DiscordConfiguration {
    public static final String EVENT_CATEGORY = "outings";
    public static final String EVENT_ARCHIVE_CATEGORY = EVENT_CATEGORY + "-archive";
    public static final String ACCEPTED_EMOJI = "✅";
    public static final String DECLINED_EMOJI = "❌";
    public static final String MAYBE_EMOJI = "❔";
    private String token;
    private long guildId;
    private long timeout;
    private String eventsRole = "events";
    private String seperatorChannel = "";
}
