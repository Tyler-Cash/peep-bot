package dev.tylercash.event.tfnsw;

import dev.tylercash.event.discord.DiscordService;
import dev.tylercash.event.event.model.Event;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Renders TfNSW noteworthy items as a single plain-content Discord message per
 * event. Week-before deltas are sent as a reply to the original post; if the
 * original message is missing, the caller is expected to fall back to a fresh
 * {@link #post(Event, List)}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TfnswReportingService {
    private static final String FIRST_HEADER =
            "🚧 Transport notice — trackwork or disruption may affect travel to this event:";
    private static final String UPDATE_HEADER = "⚠️ Update — additional disruption since the previous notice:";

    private final DiscordService discordService;

    /** Returns the new message snowflake, or null if nothing was posted. */
    public Long post(Event event, List<NoteworthyItem> items) {
        if (items.isEmpty()) return null;
        String body = FIRST_HEADER + "\n" + formatBullets(items);
        try {
            return discordService.sendContentToEventChannel(event, body);
        } catch (Exception e) {
            log.warn("Failed to post TfNSW notice to event {}: {}", event.getId(), e.getMessage());
            return null;
        }
    }

    /** Returns true if the reply succeeded; false if the parent message could not be resolved. */
    public Boolean postUpdate(Event event, long originalMessageId, List<NoteworthyItem> newItems) {
        if (newItems.isEmpty()) return true;
        String body = UPDATE_HEADER + "\n" + formatBullets(newItems);
        try {
            return discordService.replyToMessage(event, originalMessageId, body);
        } catch (Exception e) {
            log.warn("Failed to post TfNSW update to event {}: {}", event.getId(), e.getMessage());
            return false;
        }
    }

    private static String formatBullets(List<NoteworthyItem> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append('\n');
            NoteworthyItem item = items.get(i);
            String lineLabel = LineNames.distinctNames(item.routeIds()).stream()
                    .findFirst()
                    .orElse("Transport");
            sb.append("• **")
                    .append(lineLabel)
                    .append("** — ")
                    .append(item.title())
                    .append(" (<")
                    .append(item.url())
                    .append(">)");
        }
        return sb.toString();
    }
}
