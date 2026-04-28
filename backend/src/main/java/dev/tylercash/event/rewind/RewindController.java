package dev.tylercash.event.rewind;

import dev.tylercash.event.discord.GuildMembershipService;
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
    private final GuildMembershipService guildMembershipService;

    @GetMapping
    public RewindStatsDto getGuildStats(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestParam long guildId,
            @RequestParam(required = false) Integer year) {
        String snowflake = principal.getAttribute("id");
        guildMembershipService.assertMember(snowflake, guildId);
        return rewindService.getGuildStats(guildId, year);
    }

    @GetMapping("/me")
    public RewindStatsDto getMyStats(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestParam long guildId,
            @RequestParam(required = false) Integer year) {
        String snowflake = principal.getAttribute("id");
        guildMembershipService.assertMember(snowflake, guildId);
        return rewindService.getUserStats(snowflake, guildId, year);
    }

    @GetMapping("/years")
    public List<Integer> getYears(@AuthenticationPrincipal OAuth2User principal, @RequestParam long guildId) {
        String snowflake = principal.getAttribute("id");
        guildMembershipService.assertMember(snowflake, guildId);
        return rewindService.getYears(guildId);
    }
}
