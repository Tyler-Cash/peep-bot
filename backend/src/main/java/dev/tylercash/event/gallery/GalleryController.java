package dev.tylercash.event.gallery;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.GuildMembershipService;
import dev.tylercash.event.event.model.Event;
import dev.tylercash.event.immich.ImmichAlbumResponse;
import dev.tylercash.event.immich.ImmichService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/gallery")
@Tag(name = "Gallery", description = "Photo album gallery for attended events")
public class GalleryController {

    private final EventRepository eventRepository;
    private final ImmichService immichService;
    private final GuildMembershipService guildMembershipService;

    @Operation(summary = "List gallery albums", description = "Returns albums for events the user attended")
    @GetMapping
    public List<GalleryAlbumDto> getGallery(@RequestParam long guildId, @AuthenticationPrincipal OAuth2User principal) {
        String snowflake = principal.getAttribute("id");
        guildMembershipService.assertMember(snowflake, guildId);

        List<Event> events = eventRepository.findGalleryEventsForUser(guildId, snowflake);
        return events.stream()
                .map(event -> {
                    String albumId = event.getImmichAlbumId();
                    Optional<ImmichAlbumResponse> albumOpt = immichService.getAlbumDetails(albumId);
                    int assetCount =
                            albumOpt.map(ImmichAlbumResponse::assetCount).orElse(0);
                    if (assetCount == 0) {
                        return null;
                    }
                    String thumbnailUrl = "/api/gallery/thumbnail/" + albumId;
                    return new GalleryAlbumDto(
                            event.getId(), event.getName(), event.getDateTime(), albumId, thumbnailUrl, assetCount);
                })
                .filter(dto -> dto != null)
                .toList();
    }

    @Operation(
            summary = "Get album thumbnail",
            description = "Proxies the cover thumbnail from Immich with private caching")
    @GetMapping("/thumbnail/{albumId}")
    public ResponseEntity<byte[]> getAlbumThumbnail(
            @PathVariable String albumId, @AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        Optional<ImmichAlbumResponse> albumOpt = immichService.getAlbumDetails(albumId);
        if (albumOpt.isEmpty() || albumOpt.get().albumThumbnailAssetId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No thumbnail available");
        }

        Optional<byte[]> thumbnail = immichService.getThumbnail(albumOpt.get().albumThumbnailAssetId());
        if (thumbnail.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Thumbnail not found");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_JPEG);
        headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate());
        return new ResponseEntity<>(thumbnail.get(), headers, HttpStatus.OK);
    }
}
