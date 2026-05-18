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
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert.Effect;
import dev.tylercash.event.tfnsw.TfnswNoteworthyFilter.RailAlert.Severity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TfnswAlertsClientTest {

    @Test
    void parsesAlertWithStopRouteSeverityAndEffect() throws Exception {
        FeedMessage feed = FeedMessage.newBuilder()
                .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").build())
                .addEntity(FeedEntity.newBuilder()
                        .setId("entity-1")
                        .setAlert(Alert.newBuilder()
                                .setSeverityLevel(Alert.SeverityLevel.SEVERE)
                                .setEffect(Alert.Effect.MODIFIED_SERVICE)
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
        assertThat(a.effect()).isEqualTo(Effect.MODIFIED_SERVICE);
        assertThat(a.affectedStopIds()).containsExactly("CENTRAL");
        assertThat(a.affectedRouteIds()).containsExactly("T1");
        assertThat(a.startTimes()).containsExactly(Instant.ofEpochSecond(1_800_000_000L));
        assertThat(a.endTimes()).containsExactly(Instant.ofEpochSecond(1_800_010_000L));
    }

    @Test
    void mapsUnknownEffectWhenFieldAbsent() throws Exception {
        FeedMessage feed = FeedMessage.newBuilder()
                .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").build())
                .addEntity(FeedEntity.newBuilder()
                        .setId("entity-2")
                        .setAlert(Alert.newBuilder()
                                .addInformedEntity(EntitySelector.newBuilder().setRouteId("T1"))))
                .build();
        List<RailAlert> alerts = TfnswAlertsClient.parse(feed.toByteArray(), "test");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).effect()).isEqualTo(Effect.UNKNOWN);
    }

    @Test
    void parsesLiveMetroFixtureWithEffect() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/tfnsw/metro-alerts-sample.pb"));
        List<RailAlert> alerts = TfnswAlertsClient.parse(bytes, "metro");
        assertThat(alerts).hasSize(2);
        RailAlert metroTrackwork = alerts.stream()
                .filter(a -> a.affectedRouteIds().contains("SMNW_M1")
                        && a.affectedStopIds().isEmpty())
                .findFirst()
                .orElseThrow();
        assertThat(metroTrackwork.effect()).isEqualTo(Effect.MODIFIED_SERVICE);
        assertThat(metroTrackwork.severity()).isEqualTo(Severity.UNKNOWN);
        assertThat(metroTrackwork.headline()).contains("Buses replace metro services");
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
