package dev.tylercash.event.discord;

import static java.util.concurrent.TimeUnit.SECONDS;

import dev.tylercash.event.db.repository.GuildMemberRepository;
import dev.tylercash.event.db.repository.GuildMemberRepository.StaleMemberRef;
import io.micrometer.observation.annotation.Observed;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically refreshes stale entries in the Discord user cache.
 *
 * <p>The job runs a single SQL query that returns up to {@link #REFRESH_BATCH_SIZE}
 * {@code (guild_id, snowflake)} pairs needing refresh — pairs without a guild_member row, or whose
 * row is older than {@link #STALE_MINUTES}. The DB does the work; the app never materializes the
 * full attendance×guild cross-product in memory.
 *
 * <p>Discord lookups are issued per-guild as a single bulk {@code retrieveMembersByIds} Gateway
 * request instead of one REST round-trip per user. Each per-user upsert runs in its own short
 * transaction via {@code cacheService.upsertUser}, so the loop holds no DB connection across the
 * Discord I/O.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscordUserCacheRefreshJob {
    private static final int REFRESH_BATCH_SIZE = 10;
    private static final long STALE_MINUTES = 30;

    private final DiscordUserCacheService cacheService;
    private final GuildMemberRepository memberRepository;
    private final ObjectProvider<DiscordService> discordServiceProvider;

    @Observed(name = "discord.refresh-user-cache")
    @Scheduled(fixedDelay = 60, timeUnit = SECONDS)
    public void refreshStaleEntries() {
        Instant staleCutoff = Instant.now().minus(STALE_MINUTES, ChronoUnit.MINUTES);
        List<StaleMemberRef> toRefresh = memberRepository.findStaleOrMissing(staleCutoff, REFRESH_BATCH_SIZE);
        if (toRefresh.isEmpty()) {
            return;
        }

        // Group by guild so we can issue one bulk Gateway request per guild. Skip refs
        // whose snowflake isn't a numeric Discord ID — legacy attendance/event rows
        // sometimes carry a freeform username here (pre-snowflake-migration data), and
        // an unguarded parseLong used to crash the entire batch with NumberFormatException.
        Map<Long, List<String>> byGuild = new LinkedHashMap<>();
        for (StaleMemberRef ref : toRefresh) {
            if (parseSnowflakeOrNull(ref.getSnowflake()) == null) {
                log.debug(
                        "Skipping non-numeric snowflake '{}' in guild {} during cache refresh",
                        ref.getSnowflake(),
                        ref.getGuildId());
                continue;
            }
            byGuild.computeIfAbsent(ref.getGuildId(), k -> new java.util.ArrayList<>())
                    .add(ref.getSnowflake());
        }

        DiscordService discordService = discordServiceProvider.getObject();
        int refreshed = 0;
        for (Map.Entry<Long, List<String>> entry : byGuild.entrySet()) {
            long guildId = entry.getKey();
            List<String> snowflakes = entry.getValue();
            long[] ids = new long[snowflakes.size()];
            for (int i = 0; i < snowflakes.size(); i++) {
                // Safe: filter above guarantees every snowflake here is numeric.
                ids[i] = Long.parseLong(snowflakes.get(i));
            }

            List<Member> members;
            try {
                members = discordService.getMembersFromServer(guildId, ids);
            } catch (Exception e) {
                log.debug("Failed to bulk-resolve {} members in guild {}: {}", ids.length, guildId, e.getMessage());
                continue;
            }

            Map<Long, Member> byId = new HashMap<>(members.size());
            for (Member m : members) {
                byId.put(m.getIdLong(), m);
            }

            for (String snowflake : snowflakes) {
                Member member = byId.get(Long.parseLong(snowflake));
                if (member == null) {
                    continue;
                }
                try {
                    String displayName = DiscordUtil.getUserDisplayName(member);
                    String username = member.getUser().getName();
                    String avatarUrl = member.getEffectiveAvatar().getUrl(256);
                    cacheService.upsertUser(snowflake, displayName, username, avatarUrl, guildId);
                    refreshed++;
                } catch (Exception e) {
                    log.debug(
                            "Failed to upsert cache for snowflake {} in guild {}: {}",
                            snowflake,
                            guildId,
                            e.getMessage());
                }
            }
        }

        if (refreshed > 0) {
            log.debug("Refreshed {} user cache entries", refreshed);
        }
    }

    private static Long parseSnowflakeOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
