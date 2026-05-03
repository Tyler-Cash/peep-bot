package dev.tylercash.event.security;

import java.util.List;

public record UserInfoDto(
        String username,
        String displayName,
        String discordId,
        List<String> organiserGuildIds,
        List<String> ownedGuildIds,
        boolean admin,
        String avatarUrl) {}
