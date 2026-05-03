package dev.tylercash.event.security;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Snowflakes of users who have bot-admin (operator) privileges. These users
 * can toggle per-guild feature flags via /admin/* endpoints. Distinct from
 * guild owners and event organisers.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash")
public class BotAdminProperties {
    private List<String> botAdmins = List.of();
}
