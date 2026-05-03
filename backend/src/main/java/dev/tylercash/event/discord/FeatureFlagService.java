package dev.tylercash.event.discord;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FeatureFlagService {
    private final GuildRepository guildRepository;

    public boolean isEnabled(long guildId, Feature feature) {
        return guildRepository
                .findById(guildId)
                .map(g -> switch (feature) {
                    case IMMICH -> g.isImmichEnabled();
                    case GOOGLE_AUTOCOMPLETE -> g.isGoogleAutocompleteEnabled();
                    case REWIND -> g.isRewindEnabled();
                })
                .orElse(false);
    }
}
