package dev.tylercash.event.security.oauth2;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "dev.tylercash.frontend")
@Configuration
@Validated
@Data
public class FrontendConfiguration {
    private String protocol = "https";

    @NotBlank
    private String hostname;

    private String path = "/";

    public String getUrl() {
        return protocol + "://" + hostname + path;
    }
}
