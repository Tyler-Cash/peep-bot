package dev.tylercash.event.security;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserInfoDto {
    private String username;
    private String displayName;
    private String discordId;
    private boolean admin;
    private String avatarUrl;
}
