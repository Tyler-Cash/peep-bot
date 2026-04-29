package dev.tylercash.event.discord;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GuildEmojiResolver {

    public record ResolvedEmoji(String accepted, String declined, String maybe) {}

    private static final ResolvedEmoji DEFAULT = new ResolvedEmoji("✅", "❌", "❓");

    private final java.util.Map<Long, ResolvedEmoji> byGuild = new ConcurrentHashMap<>();

    public void resolve(net.dv8tion.jda.api.entities.Guild jdaGuild, Guild row) {
        long id = jdaGuild.getIdLong();
        String accepted = pick(jdaGuild, "accepted", row.getEmojiAccepted());
        String declined = pick(jdaGuild, "declined", row.getEmojiDeclined());
        String maybe = pick(jdaGuild, "maybe", row.getEmojiMaybe());
        byGuild.put(id, new ResolvedEmoji(accepted, declined, maybe));
        log.info("Resolved emoji for guild {}: {} {} {}", id, accepted, declined, maybe);
    }

    public void evict(long guildId) {
        byGuild.remove(guildId);
    }

    public ResolvedEmoji forGuild(long guildId) {
        return byGuild.getOrDefault(guildId, DEFAULT);
    }

    private String pick(net.dv8tion.jda.api.entities.Guild jdaGuild, String name, String fallback) {
        List<RichCustomEmoji> matches = jdaGuild.getEmojisByName(name, true);
        if (!matches.isEmpty()) {
            return matches.getFirst().getAsMention();
        }
        return fallback;
    }
}
