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
    private String sydneyTrainsSchedulePath = "/v1/gtfs/schedule/sydneytrains";
    private String sydneyMetroSchedulePath = "/v1/gtfs/schedule/metro";
    private double nearestStationRadiusKm = 1.5;
    private double majorEventRadiusKm = 5.0;
    private double closureRadiusKm = 2.0;

    /**
     * Days either side of the event used to detect "stale standing" rail alerts:
     * an alert whose only overlap with the event comes from an open-ended period
     * (ending more than this many days after the event — i.e. no genuine end)
     * that also started more than this many days before it. Such alerts are
     * never-closed informational notices and are dropped.
     */
    private int standingHorizonDays = 90;

    /** Days before an event that the daily follow-up poller re-checks and pins. */
    private int followUpLeadDays = 3;

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @PostConstruct
    public void log() {
        log.info("TfNSW integration {}", isEnabled() ? "enabled" : "disabled (no api-key configured)");
    }

    @Bean
    public RestClient tfnswAlertsRestClient(RestClient.Builder builder) {
        return builder.baseUrl(alertsBaseUrl)
                .defaultHeader("Authorization", "apikey " + (isEnabled() ? apiKey : "none"))
                .build();
    }

    @Bean
    public RestClient tfnswLiveTrafficRestClient(RestClient.Builder builder) {
        return builder.baseUrl(liveTrafficBaseUrl)
                .defaultHeader("Authorization", "apikey " + (isEnabled() ? apiKey : "none"))
                .build();
    }
}
