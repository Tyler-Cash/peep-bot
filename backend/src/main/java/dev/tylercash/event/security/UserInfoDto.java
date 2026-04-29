package dev.tylercash.event.security;

import java.util.List;

public record UserInfoDto(
        String username, String displayName, String discordId, List<String> adminGuildIds, String avatarUrl) {}
