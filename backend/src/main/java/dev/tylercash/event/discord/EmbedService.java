package dev.tylercash.event.discord;

import dev.tylercash.event.event.model.Event;
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
    private final GuildEmojiResolver guildEmojiResolver;

    public Collection<MessageEmbed> getMessage(Event event, Clock clock) {
        GuildEmojiResolver.ResolvedEmoji emoji = guildEmojiResolver.forGuild(event.getServerId());
        String coverImageUrl =
                (event.getCoverImageBytes() != null && event.getCoverImageBytes().length > 0 && event.getId() != null)
                        ? frontendConfiguration.getUrl() + "api/events/" + event.getId() + "/cover"
                        : null;
        return List.of(new EmbedRenderer(event, clock, frontendConfiguration.getUrl(), emoji, coverImageUrl)
                .getEmbedBuilder()
                .build());
    }
}
