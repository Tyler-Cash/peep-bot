package dev.tylercash.event.tfnsw;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TimeRange;
import com.google.transit.realtime.GtfsRealtime.TranslatedString;
import com.google.transit.realtime.GtfsRealtime.TranslatedString.Translation;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert.Severity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TfnswAlertsClientTest {

    @Test
    void parsesAlertWithStopRouteAndSeverity() throws Exception {
        FeedMessage feed = FeedMessage.newBuilder()
                .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").build())
                .addEntity(FeedEntity.newBuilder()
                        .setId("entity-1")
                        .setAlert(Alert.newBuilder()
                                .setSeverityLevel(Alert.SeverityLevel.SEVERE)
                                .setHeaderText(TranslatedString.newBuilder()
                                        .addTranslation(Translation.newBuilder()
                                                .setText("Trackwork")
                                                .build()))
                                .setDescriptionText(TranslatedString.newBuilder()
                                        .addTranslation(Translation.newBuilder()
                                                .setText("Buses replace trains")
                                                .build()))
                                .setUrl(TranslatedString.newBuilder()
                                        .addTranslation(Translation.newBuilder()
                                                .setText("https://x")
                                                .build()))
                                .addInformedEntity(EntitySelector.newBuilder()
                                        .setStopId("CENTRAL")
                                        .setRouteId("T1"))
                                .addActivePeriod(TimeRange.newBuilder()
                                        .setStart(1_800_000_000L)
                                        .setEnd(1_800_010_000L)))
                        .build())
                .build();

        List<RailAlert> alerts = TfnswAlertsClient.parse(feed.toByteArray(), "test");

        assertThat(alerts).hasSize(1);
        RailAlert a = alerts.get(0);
        assertThat(a.id()).isEqualTo("entity-1");
        assertThat(a.headline()).isEqualTo("Trackwork");
        assertThat(a.description()).isEqualTo("Buses replace trains");
        assertThat(a.url()).isEqualTo("https://x");
        assertThat(a.severity()).isEqualTo(Severity.SEVERE);
        assertThat(a.affectedStopIds()).containsExactly("CENTRAL");
        assertThat(a.affectedRouteIds()).containsExactly("T1");
        assertThat(a.start()).isEqualTo(Instant.ofEpochSecond(1_800_000_000L));
        assertThat(a.end()).isEqualTo(Instant.ofEpochSecond(1_800_010_000L));
    }

    @Test
    void skipsEntitiesWithoutAlerts() throws Exception {
        FeedMessage feed = FeedMessage.newBuilder()
                .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").build())
                .addEntity(FeedEntity.newBuilder().setId("just-a-vehicle").build())
                .build();
        assertThat(TfnswAlertsClient.parse(feed.toByteArray(), "test")).isEmpty();
    }
}
