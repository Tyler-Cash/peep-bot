package dev.tylercash.event.discord;

import static java.util.concurrent.TimeUnit.SECONDS;

import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.db.repository.GuildMemberRepository;
import dev.tylercash.event.discord.model.GuildMember;
import io.micrometer.observation.annotation.Observed;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically refreshes stale entries in the Discord user cache. Lives outside {@link
 * DiscordUserCacheService} so that each per-user upsert runs in its own short transaction via the
 * proxied call to {@code cacheService.upsertUser(...)} — the scheduled loop itself holds no
 * database connection across Discord HTTP calls.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscordUserCacheRefreshJob {
    private static final int REFRESH_BATCH_SIZE = 10;
    private static final long STALE_MINUTES = 30;

    private final DiscordUserCacheService cacheService;
    private final GuildMemberRepository memberRepository;
    private final AttendanceRepository attendanceRepository;
    private final EventRepository eventRepository;
    private final GuildRepository guildRepository;
    private final ObjectProvider<DiscordService> discordServiceProvider;

    @Observed(name = "discord.refresh-user-cache")
    @Scheduled(fixedDelay = 60, timeUnit = SECONDS)
    public void refreshStaleEntries() {
        Instant staleCutoff = Instant.now().minus(STALE_MINUTES, ChronoUnit.MINUTES);
        List<String> activeSnowflakes = new ArrayList<>(attendanceRepository.findAllDistinctSnowflakes());
        activeSnowflakes.addAll(eventRepository.findAllDistinctCreatorSnowflakes());
        if (activeSnowflakes.isEmpty()) {
            return;
        }

        List<Long> activeGuilds = guildRepository.findAllByActiveTrue().stream()
                .map(Guild::getGuildId)
                .toList();

        int refreshed = 0;
        for (String snowflake : activeSnowflakes) {
            if (refreshed >= REFRESH_BATCH_SIZE) {
                break;
            }
            for (long guildId : activeGuilds) {
                if (refreshed >= REFRESH_BATCH_SIZE) {
                    break;
                }
                Optional<GuildMember> entry = memberRepository.findByGuildIdAndSnowflake(guildId, snowflake);
                if (entry.isPresent() && entry.get().getUpdatedAt().isAfter(staleCutoff)) {
                    continue;
                }
                try {
                    Member member =
                            discordServiceProvider.getObject().getMemberFromServer(guildId, Long.parseLong(snowflake));
                    if (member != null) {
                        String displayName = DiscordUtil.getUserDisplayName(member);
                        String username = member.getUser().getName();
                        String avatarUrl = member.getEffectiveAvatar().getUrl(256);
                        cacheService.upsertUser(snowflake, displayName, username, avatarUrl, guildId);
                        refreshed++;
                    }
                } catch (Exception e) {
                    log.debug(
                            "Failed to refresh cache for snowflake {} in guild {}: {}",
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
}
