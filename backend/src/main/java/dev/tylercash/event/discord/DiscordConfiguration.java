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
    private String token;
    private long guildId;
    private long timeout;
    private String eventsRole = "events";
    private String adminRole = "event-admin";
    private String seperatorChannel = "";
    private Emoji emoji = new Emoji();

    @Data
    public static class Emoji {
        private String accepted = "\u2705";
        private String declined = "\u274C";
        private String maybe = "\u2754";
    }
}
