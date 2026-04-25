package dev.tylercash.event.discord;

import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.discord.model.DiscordUserCache;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
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

    private final DiscordUserCacheRepository cacheRepository;
    private final java.util.Random random = new java.util.Random();

    @GetMapping("/{snowflake}")
    public ResponseEntity<byte[]> getAvatar(
            @PathVariable String snowflake, @AuthenticationPrincipal OAuth2User principal) {
        String requesterSnowflake = principal != null ? principal.getAttribute("id") : null;
        if (requesterSnowflake == null || !cacheRepository.haveSharedGuild(requesterSnowflake, snowflake)) {
            return ResponseEntity.notFound().build();
        }

        Optional<DiscordUserCache> cached = cacheRepository.findById(snowflake);
        if (cached.isEmpty() || cached.get().getAvatarBytes() == null) {
            return ResponseEntity.notFound().build();
        }
        DiscordUserCache entry = cached.get();
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
}
