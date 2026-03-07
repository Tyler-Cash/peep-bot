package dev.tylercash.event.immich;

import io.micrometer.observation.annotation.Observed;
import java.util.Map;
import java.util.Optional;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Log4j2
@Service
public class ImmichService {
    private final ImmichConfiguration immichConfiguration;
    private final RestClient immichRestClient;

    public ImmichService(ImmichConfiguration immichConfiguration, RestClient immichRestClient) {
        this.immichConfiguration = immichConfiguration;
        this.immichRestClient = immichRestClient;
    }

    @Observed(name = "immich.create-album")
    public Optional<String> createAlbum(String name, String description) {
        if (!immichConfiguration.isEnabled()) {
            return Optional.empty();
        }
        try {
            String prefix = immichConfiguration.getAlbumNamePrefix();
            String albumName = (prefix != null && !prefix.isBlank()) ? prefix + name : name;
            ImmichAlbumResponse response = immichRestClient
                    .post()
                    .uri("/api/albums")
                    .body(Map.of("albumName", albumName, "description", description != null ? description : ""))
                    .retrieve()
                    .body(ImmichAlbumResponse.class);
            if (response == null || response.id() == null) {
                log.warn("Immich album creation returned null response for event: {}", name);
                return Optional.empty();
            }
            log.info("Created Immich album {} for event: {}", response.id(), name);
            return Optional.of(response.id());
        } catch (Exception e) {
            log.error("Failed to create Immich album for event: {}", name, e);
            return Optional.empty();
        }
    }

    @Observed(name = "immich.create-shared-link")
    public Optional<String> createSharedLink(String albumId) {
        if (!immichConfiguration.isEnabled()) {
            return Optional.empty();
        }
        try {
            ImmichSharedLinkResponse response = immichRestClient
                    .post()
                    .uri("/api/shared-links")
                    .body(Map.of(
                            "type", "ALBUM",
                            "albumId", albumId,
                            "allowUpload", true,
                            "allowDownload", true,
                            "showMetadata", true))
                    .retrieve()
                    .body(ImmichSharedLinkResponse.class);
            if (response == null || response.key() == null) {
                log.warn("Immich shared link creation returned null response for album: {}", albumId);
                return Optional.empty();
            }
            log.info("Created Immich shared link for album: {}", albumId);
            return Optional.of(response.key());
        } catch (Exception e) {
            log.error("Failed to create Immich shared link for album: {}", albumId, e);
            return Optional.empty();
        }
    }

    public String getShareUrl(String shareKey) {
        return immichConfiguration.getBaseUrl() + "/share/" + shareKey;
    }
}
