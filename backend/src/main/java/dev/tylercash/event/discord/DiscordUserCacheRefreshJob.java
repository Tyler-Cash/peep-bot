package dev.tylercash.event.discord;

import static java.util.concurrent.TimeUnit.SECONDS;

import dev.tylercash.event.db.repository.GuildMemberRepository;
import dev.tylercash.event.db.repository.GuildMemberRepository.StaleMemberRef;
import io.micrometer.observation.annotation.Observed;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * <p>Each per-user upsert runs in its own short transaction via {@code cacheService.upsertUser},
 * so the loop holds no DB connection across the Discord HTTP calls.
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

        DiscordService discordService = discordServiceProvider.getObject();
        int refreshed = 0;
        for (StaleMemberRef ref : toRefresh) {
            long guildId = ref.getGuildId();
            String snowflake = ref.getSnowflake();
            try {
                Member member = discordService.getMemberFromServer(guildId, Long.parseLong(snowflake));
                if (member != null) {
                    String displayName = DiscordUtil.getUserDisplayName(member);
                    String username = member.getUser().getName();
                    String avatarUrl = member.getEffectiveAvatar().getUrl(256);
                    cacheService.upsertUser(snowflake, displayName, username, avatarUrl, guildId);
                    refreshed++;
                }
            } catch (Exception e) {
                log.debug(
                        "Failed to refresh cache for snowflake {} in guild {}: {}", snowflake, guildId, e.getMessage());
            }
        }

        if (refreshed > 0) {
            log.debug("Refreshed {} user cache entries", refreshed);
        }
    }
}
