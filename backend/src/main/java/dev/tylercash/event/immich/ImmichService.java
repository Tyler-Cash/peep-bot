package dev.tylercash.event.immich;

import io.github.resilience4j.retry.Retry;
import io.micrometer.observation.annotation.Observed;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
public class ImmichService {
    private final ImmichConfiguration immichConfiguration;
    private final RestClient immichRestClient;
    private final Retry immichUploadRetry;

    public ImmichService(
            ImmichConfiguration immichConfiguration, RestClient immichRestClient, Retry immichUploadRetry) {
        this.immichConfiguration = immichConfiguration;
        this.immichRestClient = immichRestClient;
        this.immichUploadRetry = immichUploadRetry;
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
        return createSharedLink(albumId, null, null);
    }

    /**
     * Creates an Immich shared link for an album with an optional description
     * (used to disambiguate per-user share links by username + timestamp) and
     * optional expiry. Uploads/downloads/metadata visibility match the legacy
     * call signature so PrepareAlbumOperation behavior is unchanged.
     */
    @Observed(name = "immich.create-shared-link-with-expiry")
    public Optional<String> createSharedLink(String albumId, String description, Instant expiresAt) {
        if (!immichConfiguration.isEnabled()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("type", "ALBUM");
            body.put("albumId", albumId);
            body.put("allowUpload", true);
            body.put("allowDownload", true);
            body.put("showMetadata", true);
            if (description != null && !description.isBlank()) {
                body.put("description", description);
            }
            if (expiresAt != null) {
                body.put("expiresAt", expiresAt.toString());
            }
            ImmichSharedLinkResponse response = immichRestClient
                    .post()
                    .uri("/api/shared-links")
                    .body(body)
                    .retrieve()
                    .body(ImmichSharedLinkResponse.class);
            if (response == null || response.key() == null) {
                log.warn("Immich shared link creation returned null response for album: {}", albumId);
                return Optional.empty();
            }
            log.info("Created Immich shared link for album {} (description={})", albumId, description);
            return Optional.of(response.key());
        } catch (Exception e) {
            log.error("Failed to create Immich shared link for album: {}", albumId, e);
            return Optional.empty();
        }
    }

    public String getShareUrl(String shareKey) {
        return immichConfiguration.getBaseUrl() + "/share/" + shareKey;
    }

    @Observed(name = "immich.get-album")
    public Optional<ImmichAlbumResponse> getAlbumDetails(String albumId) {
        if (!immichConfiguration.isEnabled()) {
            return Optional.empty();
        }
        try {
            ImmichAlbumResponse response = immichRestClient
                    .get()
                    .uri("/api/albums/{albumId}?withoutAssets=true", albumId)
                    .retrieve()
                    .body(ImmichAlbumResponse.class);
            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.warn("Failed to fetch Immich album details for album {}: {}", albumId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Streams the asset thumbnail directly from Immich into the supplied output
     * stream without buffering the full image in JVM memory. Returns true if
     * the upstream response was successful and the body was copied through.
     */
    @Observed(name = "immich.stream-thumbnail")
    public boolean streamThumbnail(String assetId, OutputStream out) {
        if (!immichConfiguration.isEnabled()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(immichRestClient
                    .get()
                    .uri("/api/assets/{assetId}/thumbnail?size=preview", assetId)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return false;
                        }
                        try (InputStream in = response.getBody()) {
                            in.transferTo(out);
                        }
                        return true;
                    }));
        } catch (Exception e) {
            log.warn("Failed to stream thumbnail for asset {}: {}", assetId, e.getMessage());
            return false;
        }
    }

    @Observed(name = "immich.upload-asset")
    public Optional<String> uploadAsset(String filename, byte[] data, String contentType) {
        if (!immichConfiguration.isEnabled()) {
            return Optional.empty();
        }
        Supplier<Optional<String>> upload = Retry.decorateSupplier(immichUploadRetry, () -> {
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("deviceAssetId", filename);
            parts.add("deviceId", "peep-bot");
            String now = Instant.now().toString();
            parts.add("fileCreatedAt", now);
            parts.add("fileModifiedAt", now);
            HttpHeaders partHeaders = new HttpHeaders();
            partHeaders.setContentType(MediaType.parseMediaType(contentType));
            parts.add(
                    "assetData",
                    new HttpEntity<>(
                            new ByteArrayResource(data) {
                                @Override
                                public String getFilename() {
                                    return filename;
                                }
                            },
                            partHeaders));
            ImmichAssetResponse response = immichRestClient
                    .post()
                    .uri("/api/assets")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(ImmichAssetResponse.class);
            if (response == null || response.id() == null) {
                log.warn("Immich asset upload returned null response for: {}", filename);
                return Optional.empty();
            }
            log.info("Uploaded Immich asset {} for file: {}", response.id(), filename);
            return Optional.of(response.id());
        });
        try {
            return upload.get();
        } catch (Exception e) {
            log.error("Failed to upload asset to Immich after retries: {}", filename, e);
            return Optional.empty();
        }
    }

    @Observed(name = "immich.add-assets-to-album")
    public void addAssetsToAlbum(String albumId, List<String> assetIds) {
        if (!immichConfiguration.isEnabled() || assetIds.isEmpty()) {
            return;
        }
        Runnable addAssets = Retry.decorateRunnable(immichUploadRetry, () -> immichRestClient
                .put()
                .uri("/api/albums/{albumId}/assets", albumId)
                .body(Map.of("ids", assetIds))
                .retrieve()
                .toBodilessEntity());
        try {
            addAssets.run();
            log.info("Added {} assets to Immich album {}", assetIds.size(), albumId);
        } catch (Exception e) {
            log.warn("Failed to add {} assets to Immich album {} after retries", assetIds.size(), albumId, e);
        }
    }
}
