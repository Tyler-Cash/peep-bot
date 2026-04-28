package dev.tylercash.event.discord;

import dev.tylercash.event.db.repository.DiscordGuildMemberRepository;
import dev.tylercash.event.discord.model.DiscordGuildMember;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/avatar")
@RequiredArgsConstructor
public class AvatarController {

    private final DiscordGuildMemberRepository memberRepository;
    private final ObjectProvider<DiscordService> discordServiceProvider;
    private final DiscordConfiguration discordConfiguration;
    private final DiscordUserCacheService cacheService;

    @GetMapping("/{snowflake}")
    public ResponseEntity<byte[]> getAvatar(
            @PathVariable String snowflake, @AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String viewerSnowflake = principal.getAttribute("id");
        if (viewerSnowflake == null || !memberRepository.haveSharedGuild(viewerSnowflake, snowflake)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        long guildId = discordConfiguration.getGuildId();
        Optional<DiscordGuildMember> cached = memberRepository.findByGuildIdAndSnowflake(guildId, snowflake);
        if (cached.isPresent() && cached.get().getAvatarBytes() != null) {
            DiscordGuildMember entry = cached.get();
            String ct = entry.getAvatarContentType();
            MediaType mediaType;
            try {
                mediaType = (ct != null && !ct.isBlank())
                        ? MediaType.parseMediaType(ct)
                        : MediaType.parseMediaType("image/webp");
            } catch (Exception e) {
                mediaType = MediaType.parseMediaType("image/webp");
            }
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                    .body(entry.getAvatarBytes());
        }

        // Fallback: try to get from JDA and redirect or serve
        try {
            DiscordService discordService = discordServiceProvider.getIfAvailable();
            if (discordService != null) {
                Member member = discordService.getMemberFromServer(guildId, Long.parseLong(snowflake));
                if (member != null) {
                    String avatarUrl = member.getEffectiveAvatar().getUrl(256);
                    cacheService.upsertUser(
                            snowflake,
                            DiscordUtil.getUserDisplayName(member),
                            member.getUser().getName(),
                            avatarUrl,
                            guildId);

                    return ResponseEntity.status(HttpStatus.FOUND)
                            .location(URI.create(avatarUrl))
                            .build();
                }
            }
        } catch (Exception e) {
            // Ignore and fall back to 404
        }

        return ResponseEntity.notFound().build();
    }
}
