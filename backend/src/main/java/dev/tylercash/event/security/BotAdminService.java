package dev.tylercash.event.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BotAdminService {
    private final BotAdminProperties properties;

    public boolean isBotAdmin(String snowflake) {
        return snowflake != null && properties.getBotAdmins().contains(snowflake);
    }
}
