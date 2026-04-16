package dev.tylercash.event.discord;

import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.immich.ImmichService;
import dev.tylercash.event.security.oauth2.FrontendConfiguration;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import lombok.AllArgsConstructor;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class EmbedService {
    private final FrontendConfiguration frontendConfiguration;
    private final ImmichService immichService;
    private final DiscordConfiguration discordConfiguration;

    public Collection<MessageEmbed> getMessage(Event event, Clock clock) {
        String albumUrl =
                event.getImmichShareKey() != null ? immichService.getShareUrl(event.getImmichShareKey()) : null;
        return List.of(new EmbedRenderer(
                        event, clock, frontendConfiguration.getUrl(), albumUrl, discordConfiguration.getEmoji())
                .getEmbedBuilder()
                .build());
    }
}
