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
     * Hours before the event start that still count as "travelling to it". An
     * alert must be active inside the travel window to be relevant.
     */
    private int travelLeadHours = 3;

    /**
     * Hours after the event start that still count as "travelling home". Events
     * have no end time, so this covers both the event and the trip back.
     */
    private int travelTailHours = 10;

    /**
     * Days after the event beyond which an alert period's end is treated as
     * open-ended — i.e. it carries no genuine end (TfNSW defaults a missing end
     * to ~a year out).
     */
    private int standingHorizonDays = 90;

    /**
     * Open-ended alerts are live incidents or never-closed notices, so they only
     * count when they started within this many hours of the travel window.
     */
    private int openEndedLeadHours = 24;

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
