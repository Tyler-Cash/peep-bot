package dev.tylercash.event.security.oauth2;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "dev.tylercash.frontend")
@Configuration
@Data
public class FrontendConfiguration {
    private String protocol = "https";
    private String hostname;
    private String path = "/";

    public String getUrl() {
        return protocol + "://" + hostname + path;
    }
}
