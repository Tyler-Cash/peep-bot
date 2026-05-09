package dev.tylercash.event.event;

import dev.tylercash.event.db.repository.EventRepository;
import dev.tylercash.event.discord.GuildMembershipService;
import dev.tylercash.event.event.model.Event;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventCoverController {
    private final EventRepository eventRepository;
    private final GuildMembershipService guildMembershipService;

    @GetMapping("/{id}/cover")
    public ResponseEntity<byte[]> getCover(@PathVariable UUID id, @AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Event event = eventRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        guildMembershipService.assertMember(principal.getAttribute("id"), event.getServerId());
        if (event.getCoverImageBytes() == null || event.getCoverImageBytes().length == 0) {
            return ResponseEntity.notFound().build();
        }
        return toResponse(event);
    }

    private ResponseEntity<byte[]> toResponse(Event event) {
        MediaType type = event.getCoverImageContentType() != null
                ? MediaType.parseMediaType(event.getCoverImageContentType())
                : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(event.getCoverImageBytes());
    }
}
