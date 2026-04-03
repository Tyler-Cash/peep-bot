package dev.tylercash.event.security.dev;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Data
@Configuration
@Profile("local")
@ConfigurationProperties(prefix = "dev.tylercash.dev-user")
public class DevUserProperties {
    private boolean enabled = true;
    private String username = "dev-user";
    private String discordId = "123456789";
    private boolean forceAdmin = false;
}
