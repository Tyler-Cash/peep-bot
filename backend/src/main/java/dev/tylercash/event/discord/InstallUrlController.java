package dev.tylercash.event.discord;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/install-url")
@RequiredArgsConstructor
@Tag(name = "Bot install", description = "Discord bot install URL")
public class InstallUrlController {

    private final OAuth2ClientProperties oauth2ClientProperties;

    public record PermissionDto(String name, String reason) {}

    public record InstallUrlDto(String url, List<PermissionDto> permissions) {}

    @GetMapping
    public InstallUrlDto get() {
        String clientId =
                oauth2ClientProperties.getRegistration().get("discord").getClientId();
        String url = "https://discord.com/api/oauth2/authorize"
                + "?client_id=" + clientId
                + "&permissions=" + BotPermissions.REQUIRED
                + "&scope=bot";
        List<PermissionDto> permissions = Arrays.stream(BotPermission.values())
                .map(p -> new PermissionDto(p.displayName(), p.reason()))
                .toList();
        return new InstallUrlDto(url, permissions);
    }
}
