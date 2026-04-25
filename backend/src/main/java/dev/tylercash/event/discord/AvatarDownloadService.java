package dev.tylercash.event.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AvatarDownloadService {

    public record AvatarBytes(byte[] bytes, String contentType) {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public Optional<AvatarBytes> download(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.debug("Avatar download returned {} for {}", response.statusCode(), url);
                return Optional.empty();
            }
            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .map(ct -> ct.split(";")[0].trim())
                    .orElse("image/webp");
            return Optional.of(new AvatarBytes(response.body(), contentType));
        } catch (Exception e) {
            log.debug("Avatar download failed for {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    /** Build the Discord CDN URL for a user avatar hash. Returns null if hash is null or blank. */
    public static String discordAvatarUrl(String snowflake, String avatarHash) {
        if (avatarHash == null || avatarHash.isBlank()) {
            return null;
        }
        String ext = avatarHash.startsWith("a_") ? "gif" : "webp";
        return "https://cdn.discordapp.com/avatars/" + snowflake + "/" + avatarHash + "." + ext + "?size=256";
    }
}
