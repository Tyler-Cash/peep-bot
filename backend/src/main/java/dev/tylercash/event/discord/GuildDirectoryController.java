package dev.tylercash.event.discord;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/guild/{guildId}")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Guild", description = "Discord guild info")
public class GuildDirectoryController {

    private final GuildMembershipService guildMembershipService;
    private final JDA jda;

    @GetMapping("/roles")
    public List<DirectoryEntry> roles(@PathVariable String guildId, @AuthenticationPrincipal OAuth2User principal) {
        long guildIdLong = authoriseAndResolve(guildId, principal);
        Guild jdaGuild = jda.getGuildById(guildIdLong);
        if (jdaGuild == null) {
            log.debug("JDA cache miss for guild {} while listing roles", guildIdLong);
            return List.of();
        }
        return jdaGuild.getRoles().stream()
                .filter(r -> !r.isManaged())
                .filter(r -> !r.getName().equals("@everyone"))
                .map(r -> new DirectoryEntry(r.getId(), r.getName()))
                .sorted(Comparator.comparing(DirectoryEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @GetMapping("/categories")
    public List<DirectoryEntry> categories(
            @PathVariable String guildId, @AuthenticationPrincipal OAuth2User principal) {
        long guildIdLong = authoriseAndResolve(guildId, principal);
        Guild jdaGuild = jda.getGuildById(guildIdLong);
        if (jdaGuild == null) {
            log.debug("JDA cache miss for guild {} while listing categories", guildIdLong);
            return List.of();
        }
        return jdaGuild.getCategories().stream()
                .map(c -> new DirectoryEntry(c.getId(), c.getName()))
                .sorted(Comparator.comparing(DirectoryEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private long authoriseAndResolve(String guildId, OAuth2User principal) {
        String snowflake = principal.getAttribute("id");
        long guildIdLong = Long.parseLong(guildId);
        guildMembershipService.assertMember(snowflake, guildIdLong);
        return guildIdLong;
    }
}
