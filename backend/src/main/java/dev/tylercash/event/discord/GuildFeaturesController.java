package dev.tylercash.event.discord;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/guild")
@RequiredArgsConstructor
@Tag(name = "Guild", description = "Discord guild info")
public class GuildFeaturesController {

    private final GuildMembershipService guildMembershipService;
    private final FeatureFlagService featureFlagService;

    @GetMapping("/{guildId}/features")
    public Map<String, Boolean> getFeatures(
            @PathVariable String guildId, @AuthenticationPrincipal OAuth2User principal) {
        String snowflake = principal.getAttribute("id");
        long guildIdLong = Long.parseLong(guildId);
        guildMembershipService.assertMember(snowflake, guildIdLong);
        return Map.of(
                "immichEnabled",
                featureFlagService.isEnabled(guildIdLong, Feature.IMMICH),
                "googleAutocompleteEnabled",
                featureFlagService.isEnabled(guildIdLong, Feature.GOOGLE_AUTOCOMPLETE),
                "rewindEnabled",
                featureFlagService.isEnabled(guildIdLong, Feature.REWIND),
                "contractsEnabled",
                featureFlagService.isEnabled(guildIdLong, Feature.CONTRACTS));
    }
}
