package dev.tylercash.event.discord;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.discord")
public class DiscordConfiguration {
    public static final String EVENT_CATEGORY = "outings";
    public static final String EVENT_ARCHIVE_CATEGORY = EVENT_CATEGORY + "-archive";

    @NotBlank
    private String token;

    private long timeout;
}
