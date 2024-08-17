package dev.tylercash.event.discord;

import org.javacord.api.DiscordApi;
import org.springframework.stereotype.Component;

import static org.mockito.Mockito.mock;

@Component
class DiscordConfigurationTest {
    
    public DiscordApi discordClient() {
        return mock(DiscordApi.class);
    }

}