package dev.tylercash.event.discord;

import static java.util.concurrent.TimeUnit.SECONDS;

import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.discord.model.DiscordUserCache;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Log4j2
@Service
@RequiredArgsConstructor
public class DiscordUserCacheService {
    private static final int REFRESH_BATCH_SIZE = 10;
    private static final long STALE_MINUTES = 30;

    private final DiscordUserCacheRepository cacheRepository;
    private final AttendanceRepository attendanceRepository;
    private final DiscordService discordService;
    private final DiscordConfiguration discordConfiguration;

    public void upsertUser(String snowflake, String displayName) {
        cacheRepository.save(new DiscordUserCache(snowflake, displayName, Instant.now()));
    }

    public String getDisplayName(String snowflake) {
        if (snowflake == null || snowflake.isBlank()) {
            return "Unknown User";
        }
        return cacheRepository
                .findById(snowflake)
                .map(DiscordUserCache::getDisplayName)
                .orElse("Unknown User (#" + snowflake.substring(Math.max(0, snowflake.length() - 4)) + ")");
    }

    public Map<String, String> getDisplayNames(Collection<String> snowflakes) {
        if (snowflakes == null || snowflakes.isEmpty()) {
            return Map.of();
        }
        Set<String> unique =
                snowflakes.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.toSet());
        if (unique.isEmpty()) {
            return Map.of();
        }
        return cacheRepository.findAllBySnowflakeIn(unique).stream()
                .collect(Collectors.toMap(DiscordUserCache::getSnowflake, DiscordUserCache::getDisplayName));
    }

    @Scheduled(fixedDelay = 60, timeUnit = SECONDS)
    public void refreshStaleEntries() {
        Instant staleCutoff = Instant.now().minus(STALE_MINUTES, ChronoUnit.MINUTES);
        List<String> activeSnowflakes = attendanceRepository.findAllDistinctSnowflakes();
        if (activeSnowflakes.isEmpty()) {
            return;
        }

        Map<String, DiscordUserCache> cached = cacheRepository.findAllBySnowflakeIn(activeSnowflakes).stream()
                .collect(Collectors.toMap(DiscordUserCache::getSnowflake, Function.identity()));

        List<String> toRefresh = activeSnowflakes.stream()
                .filter(s -> {
                    DiscordUserCache entry = cached.get(s);
                    return entry == null || entry.getUpdatedAt().isBefore(staleCutoff);
                })
                .limit(REFRESH_BATCH_SIZE)
                .toList();

        for (String snowflake : toRefresh) {
            try {
                Member member = discordService.getMemberFromServer(
                        discordConfiguration.getGuildId(), Long.parseLong(snowflake));
                if (member != null) {
                    String displayName = DiscordUtil.getUserDisplayName(member);
                    upsertUser(snowflake, displayName);
                }
            } catch (Exception e) {
                log.debug("Failed to refresh cache for snowflake {}: {}", snowflake, e.getMessage());
            }
        }

        if (!toRefresh.isEmpty()) {
            log.debug("Refreshed {} user cache entries", toRefresh.size());
        }
    }
}
