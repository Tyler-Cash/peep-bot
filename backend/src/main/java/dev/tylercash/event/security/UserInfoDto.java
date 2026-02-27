package dev.tylercash.event.security;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserInfoDto {
    private String username;
    private String discordId;
    private boolean admin;
}
