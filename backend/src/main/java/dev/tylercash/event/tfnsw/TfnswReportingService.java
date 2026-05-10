package dev.tylercash.event.tfnsw;

import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.tfnsw.NoteworthyItem.Source;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.springframework.stereotype.Service;

/**
 * Composes a Discord embed summarising noteworthy TfNSW items for a given event
 * and posts it to the event's Discord channel via {@link DiscordService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TfnswReportingService {
    private static final int TFNSW_BLUE = 0x0072CE;

    private final DiscordService discordService;

    public void post(Event event, List<NoteworthyItem> items, boolean isUpdate) {
        if (items.isEmpty()) return;

        StringBuilder body = new StringBuilder();
        appendSection(body, items, Source.RAIL_METRO, "🚆 Transport");
        appendSection(body, items, Source.TRAFFIC, "🚗 Traffic");

        MessageEmbed embed = new EmbedBuilder()
                .setTitle((isUpdate ? "Update: " : "") + "Travel notice for " + event.getName())
                .setColor(TFNSW_BLUE)
                .setDescription(body.toString())
                .setFooter("Source: Transport for NSW")
                .build();

        try {
            discordService.sendEmbedToEventChannel(event, embed);
        } catch (Exception e) {
            log.warn("Failed to post TfNSW embed to event {}: {}", event.getId(), e.getMessage());
        }
    }

    private static void appendSection(StringBuilder body, List<NoteworthyItem> items, Source source, String header) {
        var matching = items.stream().filter(i -> i.source() == source).toList();
        if (matching.isEmpty()) return;
        body.append("**").append(header).append("**\n");
        for (var i : matching) {
            body.append("• [")
                    .append(i.title())
                    .append("](")
                    .append(i.url())
                    .append(") — ")
                    .append(i.detail())
                    .append('\n');
        }
        body.append('\n');
    }
}
