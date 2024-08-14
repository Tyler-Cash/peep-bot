package dev.tylercash.event.discord;

import org.javacord.api.DiscordApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import static org.mockito.Mockito.mock;

@Component
class DiscordConfiguration {
    @Bean
    @Primary
    public DiscordApi discordClient() {
        return mock(DiscordApi.class);
    }

}