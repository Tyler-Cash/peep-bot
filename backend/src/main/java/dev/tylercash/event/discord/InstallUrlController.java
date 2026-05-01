package dev.tylercash.event.discord;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/install-url")
@RequiredArgsConstructor
@Tag(name = "Bot install", description = "Discord bot install URL")
public class InstallUrlController {

    private final OAuth2ClientProperties oauth2ClientProperties;

    @GetMapping
    public Map<String, String> get() {
        String clientId =
                oauth2ClientProperties.getRegistration().get("discord").getClientId();
        String url = "https://discord.com/api/oauth2/authorize"
                + "?client_id=" + clientId
                + "&permissions=" + BotPermissions.REQUIRED
                + "&scope=bot+applications.commands";
        return Map.of("url", url);
    }
}
