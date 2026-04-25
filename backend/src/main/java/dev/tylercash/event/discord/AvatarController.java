package dev.tylercash.event.discord;

import dev.tylercash.event.db.repository.DiscordUserCacheRepository;
import dev.tylercash.event.discord.model.DiscordUserCache;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/avatar")
@RequiredArgsConstructor
public class AvatarController {

    private final DiscordUserCacheRepository cacheRepository;

    @GetMapping("/{snowflake}")
    public ResponseEntity<byte[]> getAvatar(@PathVariable String snowflake) {
        Optional<DiscordUserCache> cached = cacheRepository.findById(snowflake);
        if (cached.isEmpty() || cached.get().getAvatarBytes() == null) {
            return ResponseEntity.notFound().build();
        }
        DiscordUserCache entry = cached.get();
        MediaType mediaType = MediaType.parseMediaType(
                entry.getAvatarContentType() != null ? entry.getAvatarContentType() : "image/webp");
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .body(entry.getAvatarBytes());
    }
}
