package dev.tylercash.event.tfnsw;

import java.util.Set;

/**
 * Curated allowlist of "major" Sydney rail and metro stations. Alerts that affect
 * any of these stations are always considered noteworthy by {@link TfnswNoteworthyFilter},
 * regardless of geographic distance from the event venue.
 *
 * <p>Stop IDs are placeholders — they MUST be replaced with real TfNSW GTFS stop IDs
 * (sourced from the current Sydney Trains + Sydney Metro GTFS feed) before this
 * integration is enabled in any production guild. The placeholders allow the
 * filter logic and tests to be developed independently of the live GTFS data.
 */
public final class MajorStations {
    public static final int EXPECTED_SIZE = 13;

    public static final Set<String> STOP_IDS = Set.of(
            // Sydney Trains hubs (PLACEHOLDER IDs)
            "CENTRAL",
            "TOWN_HALL",
            "WYNYARD",
            "CIRCULAR_QUAY",
            "MARTIN_PLACE",
            "MUSEUM",
            "ST_JAMES",
            "CHATSWOOD",
            "PARRAMATTA",
            "NORTH_SYDNEY",
            "BONDI_JUNCTION",
            "STRATHFIELD",
            "HURSTVILLE");

    private MajorStations() {}
}
