package dev.tylercash.event.discord;

import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.db.repository.GuildMemberRepository;
import dev.tylercash.event.discord.AvatarDownloadService.AvatarBytes;
import dev.tylercash.event.discord.model.DiscordUserCache;
import dev.tylercash.event.discord.model.GuildMember;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordUserCacheService {

    private final DiscordUserCacheRepository cacheRepository;
    private final GuildMemberRepository memberRepository;
    private final AvatarDownloadService avatarDownloadService;

    /** Upsert global user info (username) and per-guild info (displayName, avatar). */
    @Transactional
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

        GuildMember member = memberRepository
                .findByGuildIdAndSnowflake(guildId, snowflake)
                .orElseGet(() -> new GuildMember(guildId, snowflake, null, null, null, Instant.now()));
        member.setDisplayName(displayName);
        if (avatarBytes != null) {
            member.setAvatarBytes(avatarBytes);
            member.setAvatarContentType(avatarContentType);
        }
        member.setUpdatedAt(Instant.now());
        memberRepository.save(member);
    }

    /** Remove a single member's row for a guild (e.g. on GuildMemberRemoveEvent). */
    @Transactional
    public void removeMember(long guildId, String snowflake) {
        if (snowflake == null || snowflake.isBlank()) {
            return;
        }
        memberRepository.deleteById(new GuildMember.PK(guildId, snowflake));
    }

    /**
     * Delete guild_member rows for {@code guildId} whose snowflake isn't in {@code liveSnowflakes}.
     * No-op if the live set is empty (avoids wiping a guild when a load returns nothing).
     */
    @Transactional
    public int pruneMembersNotIn(long guildId, Set<String> liveSnowflakes) {
        if (liveSnowflakes == null || liveSnowflakes.isEmpty()) {
            return 0;
        }
        return memberRepository.deleteByGuildIdAndSnowflakeNotIn(guildId, liveSnowflakes);
    }

    /** Register a guild membership without overwriting an existing displayName/avatar. */
    @Transactional
    public void registerIfMissing(String snowflake, String displayName, String username, long guildId) {
        upsertGlobal(snowflake, username);

        if (memberRepository.findByGuildIdAndSnowflake(guildId, snowflake).isEmpty()) {
            memberRepository.save(new GuildMember(guildId, snowflake, displayName, null, null, Instant.now()));
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

    /** Lookup a per-guild display name, falling back to the global username then "Unknown User (#…)". */
    public String getDisplayName(long guildId, String snowflake) {
        if (snowflake == null || snowflake.isBlank()) {
            return "Unknown User";
        }
        return memberRepository
                .findByGuildIdAndSnowflake(guildId, snowflake)
                .map(GuildMember::getDisplayName)
                .filter(s -> s != null && !s.isBlank())
                .orElseGet(() -> cacheRepository
                        .findById(snowflake)
                        .map(DiscordUserCache::getUsername)
                        .filter(s -> s != null && !s.isBlank())
                        .orElse("Unknown User (#" + snowflake.substring(Math.max(0, snowflake.length() - 4)) + ")"));
    }

    public Map<String, String> getDisplayNames(long guildId, Collection<String> snowflakes) {
        Set<String> unique = uniqueSnowflakes(snowflakes);
        if (unique.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = memberRepository.findAllByGuildIdAndSnowflakeIn(guildId, unique).stream()
                .filter(m -> m.getDisplayName() != null && !m.getDisplayName().isBlank())
                .collect(Collectors.toMap(GuildMember::getSnowflake, GuildMember::getDisplayName));
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
    public Map<String, GuildMember> getGuildMembers(long guildId, Collection<String> snowflakes) {
        Set<String> unique = uniqueSnowflakes(snowflakes);
        if (unique.isEmpty()) {
            return Map.of();
        }
        return memberRepository.findAllByGuildIdAndSnowflakeIn(guildId, unique).stream()
                .collect(Collectors.toMap(GuildMember::getSnowflake, Function.identity()));
    }

    /** Global usernames keyed by snowflake (no per-guild context). */
    public Map<String, String> getUsernames(Collection<String> snowflakes) {
        Set<String> unique = uniqueSnowflakes(snowflakes);
        if (unique.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        cacheRepository.findAllBySnowflakeIn(unique).stream()
                .filter(u -> u.getUsername() != null)
                .forEach(u -> result.put(u.getSnowflake(), u.getUsername()));
        return result;
    }

    private static Set<String> uniqueSnowflakes(Collection<String> snowflakes) {
        if (snowflakes == null || snowflakes.isEmpty()) {
            return Set.of();
        }
        return snowflakes.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.toSet());
    }
}
