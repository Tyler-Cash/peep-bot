package dev.tylercash.event.discord;

import static java.util.concurrent.TimeUnit.SECONDS;

import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.AvatarDownloadService.AvatarBytes;
import dev.tylercash.event.discord.model.DiscordUserCache;
import io.micrometer.observation.annotation.Observed;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
public class DiscordUserCacheService {
    private static final int REFRESH_BATCH_SIZE = 10;
    private static final long STALE_MINUTES = 30;

    private final DiscordUserCacheRepository cacheRepository;
    private final AttendanceRepository attendanceRepository;
    private final EventRepository eventRepository;
    private final ObjectProvider<DiscordService> discordServiceProvider;
    private final DiscordConfiguration discordConfiguration;
    private final AvatarDownloadService avatarDownloadService;

    public DiscordUserCacheService(
            DiscordUserCacheRepository cacheRepository,
            AttendanceRepository attendanceRepository,
            EventRepository eventRepository,
            ObjectProvider<DiscordService> discordServiceProvider,
            DiscordConfiguration discordConfiguration,
            AvatarDownloadService avatarDownloadService) {
        this.cacheRepository = cacheRepository;
        this.attendanceRepository = attendanceRepository;
        this.eventRepository = eventRepository;
        this.discordServiceProvider = discordServiceProvider;
        this.discordConfiguration = discordConfiguration;
        this.avatarDownloadService = avatarDownloadService;
    }

    public void upsertUser(String snowflake, String displayName, String avatarUrl, long guildId) {
        byte[] avatarBytes = null;
        String avatarContentType = null;
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            Optional<AvatarBytes> downloaded = avatarDownloadService.download(avatarUrl);
            if (downloaded.isPresent()) {
                avatarBytes = downloaded.get().bytes();
                avatarContentType = downloaded.get().contentType();
            }
        }

        Optional<DiscordUserCache> existing = cacheRepository.findById(snowflake);
        DiscordUserCache user = existing.orElse(new DiscordUserCache(
                snowflake, displayName, Instant.now(), avatarBytes, avatarContentType, new HashSet<>()));

        user.setDisplayName(displayName);
        user.setUpdatedAt(Instant.now());
        user.setAvatarBytes(avatarBytes);
        user.setAvatarContentType(avatarContentType);
        user.getGuildIds().add(guildId);

        cacheRepository.save(user);
    }

    public void registerIfMissing(String snowflake, String displayName, long guildId) {
        Optional<DiscordUserCache> existing = cacheRepository.findById(snowflake);
        if (existing.isEmpty()) {
            DiscordUserCache newUser =
                    new DiscordUserCache(snowflake, displayName, Instant.now(), null, null, new HashSet<>());
            newUser.getGuildIds().add(guildId);
            cacheRepository.save(newUser);
        } else {
            DiscordUserCache user = existing.get();
            if (user.getGuildIds().add(guildId)) {
                user.setUpdatedAt(Instant.now());
                cacheRepository.save(user);
            }
        }
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

    @Observed(name = "discord.refresh-user-cache")
    @Scheduled(fixedDelay = 60, timeUnit = SECONDS)
    public void refreshStaleEntries() {
        Instant staleCutoff = Instant.now().minus(STALE_MINUTES, ChronoUnit.MINUTES);
        List<String> activeSnowflakes = new ArrayList<>(attendanceRepository.findAllDistinctSnowflakes());
        activeSnowflakes.addAll(eventRepository.findAllDistinctCreatorSnowflakes());
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
                Member member = discordServiceProvider
                        .getObject()
                        .getMemberFromServer(discordConfiguration.getGuildId(), Long.parseLong(snowflake));
                if (member != null) {
                    String displayName = DiscordUtil.getUserDisplayName(member);
                    String avatarUrl = member.getEffectiveAvatar().getUrl(256);
                    upsertUser(snowflake, displayName, avatarUrl, discordConfiguration.getGuildId());
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
