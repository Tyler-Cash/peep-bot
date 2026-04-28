package dev.tylercash.event.discord;

import static java.util.concurrent.TimeUnit.SECONDS;

import dev.tylercash.event.db.repository.AttendanceRepository;
import dev.tylercash.event.db.repository.DiscordGuildMemberRepository;
import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.AvatarDownloadService.AvatarBytes;
import dev.tylercash.event.discord.model.DiscordGuildMember;
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
    private final DiscordGuildMemberRepository memberRepository;
    private final AttendanceRepository attendanceRepository;
    private final EventRepository eventRepository;
    private final ObjectProvider<DiscordService> discordServiceProvider;
    private final DiscordConfiguration discordConfiguration;
    private final AvatarDownloadService avatarDownloadService;

    public DiscordUserCacheService(
            DiscordUserCacheRepository cacheRepository,
            DiscordGuildMemberRepository memberRepository,
            AttendanceRepository attendanceRepository,
            EventRepository eventRepository,
            ObjectProvider<DiscordService> discordServiceProvider,
            DiscordConfiguration discordConfiguration,
            AvatarDownloadService avatarDownloadService) {
        this.cacheRepository = cacheRepository;
        this.memberRepository = memberRepository;
        this.attendanceRepository = attendanceRepository;
        this.eventRepository = eventRepository;
        this.discordServiceProvider = discordServiceProvider;
        this.discordConfiguration = discordConfiguration;
        this.avatarDownloadService = avatarDownloadService;
    }

    /** Upsert global user info (username) and per-guild info (displayName, avatar). */
    public void upsertUser(String snowflake, String displayName, String username, String avatarUrl, long guildId) {
        upsertGlobal(snowflake, username);

        byte[] avatarBytes = null;
        String avatarContentType = null;
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            Optional<AvatarBytes> downloaded = avatarDownloadService.download(avatarUrl);
            if (downloaded.isPresent()) {
                avatarBytes = downloaded.get().bytes();
                avatarContentType = downloaded.get().contentType();
            }
        }

        DiscordGuildMember member = memberRepository
                .findByGuildIdAndSnowflake(guildId, snowflake)
                .orElseGet(() -> new DiscordGuildMember(guildId, snowflake, null, null, null, Instant.now()));
        member.setDisplayName(displayName);
        if (avatarBytes != null) {
            member.setAvatarBytes(avatarBytes);
            member.setAvatarContentType(avatarContentType);
        }
        member.setUpdatedAt(Instant.now());
        memberRepository.save(member);
    }

    /** Register a guild membership without overwriting an existing displayName/avatar. */
    public void registerIfMissing(String snowflake, String displayName, String username, long guildId) {
        upsertGlobal(snowflake, username);

        Optional<DiscordGuildMember> existing = memberRepository.findByGuildIdAndSnowflake(guildId, snowflake);
        if (existing.isEmpty()) {
            memberRepository.save(new DiscordGuildMember(guildId, snowflake, displayName, null, null, Instant.now()));
        }
    }

    private void upsertGlobal(String snowflake, String username) {
        DiscordUserCache user =
                cacheRepository.findById(snowflake).orElseGet(() -> new DiscordUserCache(snowflake, null, null));
        if (username != null && !username.equals(user.getUsername())) {
            user.setUsername(username);
        }
        user.setUpdatedAt(Instant.now());
        cacheRepository.save(user);
    }

    /** Lookup a per-guild display name. */
    public String getDisplayName(long guildId, String snowflake) {
        if (snowflake == null || snowflake.isBlank()) {
            return "Unknown User";
        }
        return memberRepository
                .findByGuildIdAndSnowflake(guildId, snowflake)
                .map(DiscordGuildMember::getDisplayName)
                .filter(s -> s != null && !s.isBlank())
                .orElseGet(() -> cacheRepository
                        .findById(snowflake)
                        .map(DiscordUserCache::getUsername)
                        .filter(s -> s != null && !s.isBlank())
                        .orElse("Unknown User (#" + snowflake.substring(Math.max(0, snowflake.length() - 4)) + ")"));
    }

    public Map<String, String> getDisplayNames(long guildId, Collection<String> snowflakes) {
        if (snowflakes == null || snowflakes.isEmpty()) {
            return Map.of();
        }
        Set<String> unique =
                snowflakes.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.toSet());
        if (unique.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = memberRepository.findAllByGuildIdAndSnowflakeIn(guildId, unique).stream()
                .filter(m -> m.getDisplayName() != null && !m.getDisplayName().isBlank())
                .collect(Collectors.toMap(DiscordGuildMember::getSnowflake, DiscordGuildMember::getDisplayName));
        // Fill blanks with global username so callers always get a name to render.
        Set<String> missing = new HashSet<>(unique);
        missing.removeAll(result.keySet());
        if (!missing.isEmpty()) {
            cacheRepository.findAllBySnowflakeIn(missing).stream()
                    .filter(u -> u.getUsername() != null && !u.getUsername().isBlank())
                    .forEach(u -> result.put(u.getSnowflake(), u.getUsername()));
        }
        return result;
    }

    /** Per-guild members keyed by snowflake. */
    public Map<String, DiscordGuildMember> getGuildMembers(long guildId, Collection<String> snowflakes) {
        if (snowflakes == null || snowflakes.isEmpty()) {
            return Map.of();
        }
        Set<String> unique =
                snowflakes.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.toSet());
        if (unique.isEmpty()) {
            return Map.of();
        }
        return memberRepository.findAllByGuildIdAndSnowflakeIn(guildId, unique).stream()
                .collect(Collectors.toMap(DiscordGuildMember::getSnowflake, Function.identity()));
    }

    /** Global usernames keyed by snowflake (no per-guild context). */
    public Map<String, String> getUsernames(Collection<String> snowflakes) {
        if (snowflakes == null || snowflakes.isEmpty()) {
            return Map.of();
        }
        Set<String> unique =
                snowflakes.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.toSet());
        if (unique.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        cacheRepository.findAllBySnowflakeIn(unique).stream()
                .filter(u -> u.getUsername() != null)
                .forEach(u -> result.put(u.getSnowflake(), u.getUsername()));
        return result;
    }

    @Observed(name = "discord.refresh-user-cache")
    @Scheduled(fixedDelay = 60, timeUnit = SECONDS)
    public void refreshStaleEntries() {
        long guildId = discordConfiguration.getGuildId();
        Instant staleCutoff = Instant.now().minus(STALE_MINUTES, ChronoUnit.MINUTES);
        List<String> activeSnowflakes = new ArrayList<>(attendanceRepository.findAllDistinctSnowflakes());
        activeSnowflakes.addAll(eventRepository.findAllDistinctCreatorSnowflakes());
        if (activeSnowflakes.isEmpty()) {
            return;
        }

        Map<String, DiscordGuildMember> cached =
                memberRepository.findAllByGuildIdAndSnowflakeIn(guildId, activeSnowflakes).stream()
                        .collect(Collectors.toMap(DiscordGuildMember::getSnowflake, Function.identity()));

        List<String> toRefresh = activeSnowflakes.stream()
                .filter(s -> {
                    DiscordGuildMember entry = cached.get(s);
                    return entry == null || entry.getUpdatedAt().isBefore(staleCutoff);
                })
                .limit(REFRESH_BATCH_SIZE)
                .toList();

        for (String snowflake : toRefresh) {
            try {
                Member member =
                        discordServiceProvider.getObject().getMemberFromServer(guildId, Long.parseLong(snowflake));
                if (member != null) {
                    String displayName = DiscordUtil.getUserDisplayName(member);
                    String username = member.getUser().getName();
                    String avatarUrl = member.getEffectiveAvatar().getUrl(256);
                    upsertUser(snowflake, displayName, username, avatarUrl, guildId);
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
