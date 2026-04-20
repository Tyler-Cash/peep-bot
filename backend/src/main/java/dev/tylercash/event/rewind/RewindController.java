package dev.tylercash.event.rewind;

import dev.tylercash.event.rewind.model.RewindStatsDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rewind")
@RequiredArgsConstructor
@Tag(name = "Rewind", description = "Event history statistics")
public class RewindController {

    private final RewindService rewindService;

    @GetMapping
    public RewindStatsDto getGuildStats(@RequestParam(required = false) Integer year) {
        return rewindService.getGuildStats(year);
    }

    @GetMapping("/me")
    public RewindStatsDto getMyStats(
            @AuthenticationPrincipal OAuth2User principal, @RequestParam(required = false) Integer year) {
        String snowflake = principal.getAttribute("id");
        return rewindService.getUserStats(snowflake, year);
    }

    @GetMapping("/years")
    public List<Integer> getYears() {
        return rewindService.getYears();
    }
}
