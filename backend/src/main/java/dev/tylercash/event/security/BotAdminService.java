package dev.tylercash.event.security;

import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BotAdminService {
    private final BotAdminProperties properties;

    @PostConstruct
    void logConfiguration() {
        List<String> admins = properties.getBotAdmins();
        if (admins.isEmpty()) {
            log.warn(
                    "No bot-admins configured (dev.tylercash.bot-admins is empty). Admin endpoints will reject every request.");
        } else {
            List<String> trimmed =
                    admins.stream().filter(a -> a != null).map(String::trim).toList();
            log.info("Bot admin allowlist: {} ({} entries)", trimmed, trimmed.size());
        }
    }

    public boolean isBotAdmin(String snowflake) {
        if (snowflake == null) return false;
        String trimmed = snowflake.trim();
        return properties.getBotAdmins().stream()
                .filter(a -> a != null)
                .map(String::trim)
                .anyMatch(trimmed::equals);
    }
}
