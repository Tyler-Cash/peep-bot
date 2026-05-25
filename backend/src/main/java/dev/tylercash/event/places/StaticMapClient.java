package dev.tylercash.event.places;

import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fetches a styled Google Static Maps tile for use as the inset on the Discord embed composite. The styling mirrors the
 * frontend proxy ({@code /api/places/staticmap}) so the bot's embed and the web UI render the same warm-paper map.
 */
@Slf4j
@Component
public class StaticMapClient {
    private static final List<String> STYLES = List.of(
            "feature:poi|visibility:off",
            "feature:transit|visibility:off",
            "element:labels|visibility:off",
            "feature:landscape|element:geometry|color:0xf2efe6",
            "feature:road|element:geometry|color:0xffffff",
            "feature:road|element:geometry.stroke|color:0xdfd6c0",
            "feature:water|element:geometry|color:0xa5d8e0",
            "feature:poi.park|element:geometry|color:0xc8e5b0");

    private final RestClient mapsStaticRestClient;
    private final PlacesConfiguration config;

    public StaticMapClient(
            @Qualifier("mapsStaticRestClient") RestClient mapsStaticRestClient, PlacesConfiguration config) {
        this.mapsStaticRestClient = mapsStaticRestClient;
        this.config = config;
    }

    /**
     * @param placeId Google place id; must be non-blank.
     * @param sizePx output square size (Google caps free-tier scale=2 at 640 raw, so caller should stay ≤ 640).
     */
    public Optional<byte[]> fetchStyledMap(String placeId, int sizePx) {
        if (!config.isEnabled() || placeId == null || placeId.isBlank()) {
            return Optional.empty();
        }
        try {
            ResponseEntity<byte[]> res = mapsStaticRestClient
                    .get()
                    .uri(builder -> {
                        builder.path("/maps/api/staticmap")
                                .queryParam("center", "place_id:" + placeId)
                                .queryParam("zoom", 15)
                                .queryParam("size", sizePx + "x" + sizePx)
                                .queryParam("scale", 2)
                                .queryParam("maptype", "roadmap");
                        for (String s : STYLES) {
                            builder.queryParam("style", s);
                        }
                        // Tiny fallback marker so degraded clients (those that fail to fetch the
                        // composite and render the raw Google map directly) still see a pin.
                        builder.queryParam("markers", "size:tiny|color:0x7BC24F|place_id:" + placeId);
                        return builder.queryParam("key", config.getApiKey()).build();
                    })
                    .retrieve()
                    .toEntity(byte[].class);
            byte[] body = res.getBody();
            if (body == null || body.length == 0) {
                return Optional.empty();
            }
            return Optional.of(body);
        } catch (Exception e) {
            log.warn("Failed to fetch static map for place_id {}: {}", placeId, e.getMessage());
            return Optional.empty();
        }
    }
}
