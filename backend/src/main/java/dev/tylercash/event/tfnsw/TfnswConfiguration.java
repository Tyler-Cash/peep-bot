package dev.tylercash.event.tfnsw;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "dev.tylercash.tfnsw")
public class TfnswConfiguration {
    private String apiKey = "";
    private String alertsBaseUrl = "https://api.transport.nsw.gov.au";
    private String liveTrafficBaseUrl = "https://api.transport.nsw.gov.au";
    private double nearestStationRadiusKm = 1.5;
    private double majorEventRadiusKm = 5.0;
    private double closureRadiusKm = 2.0;

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @PostConstruct
    public void log() {
        log.info("TfNSW integration {}", isEnabled() ? "enabled" : "disabled (no api-key configured)");
    }

    @Bean
    public RestClient tfnswAlertsRestClient() {
        return RestClient.builder()
                .baseUrl(alertsBaseUrl)
                .defaultHeader("Authorization", "apikey " + (isEnabled() ? apiKey : "none"))
                .build();
    }

    @Bean
    public RestClient tfnswLiveTrafficRestClient() {
        return RestClient.builder()
                .baseUrl(liveTrafficBaseUrl)
                .defaultHeader("Authorization", "apikey " + (isEnabled() ? apiKey : "none"))
                .build();
    }
}
