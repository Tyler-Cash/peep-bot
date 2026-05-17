package dev.tylercash.event.tfnsw;

import java.util.Set;

/**
 * Hand-listed Sydney rail/metro line identifiers used by the TfNSW noteworthy
 * filter to decide whether a GTFS-realtime alert affects a citywide backbone
 * line. The TfNSW GTFS feeds publish routes with these prefixes:
 *
 * <ul>
 *   <li>{@code SMNW} — Sydney Metro Northwest (Tallawong↔Sydenham)</li>
 *   <li>{@code T1}..{@code T9} — Sydney Trains lines</li>
 *   <li>{@code APS} — Airport &amp; South</li>
 *   <li>{@code BMT} — Blue Mountains</li>
 *   <li>{@code BNK} — Bankstown</li>
 *   <li>{@code CCN} — Central Coast &amp; Newcastle</li>
 *   <li>{@code ESI} — Eastern Suburbs &amp; Illawarra</li>
 *   <li>{@code IWL} — Inner West &amp; Leppington</li>
 *   <li>{@code NSN} — North Shore &amp; Northern</li>
 *   <li>{@code NTH} — Northern</li>
 *   <li>{@code OLY} — Olympic Park</li>
 *   <li>{@code SCO} — South Coast</li>
 *   <li>{@code WST} — Western</li>
 * </ul>
 *
 * <p>An alert "touches" a backbone line if any of its informed route_ids is
 * either exactly one of the prefixes above, or starts with {@code "<prefix>_"}
 * (the feed publishes per-direction and per-pattern variants like
 * {@code APS_1a}, {@code SMNW_M1}). Trip-level route_ids (e.g.
 * {@code 4T.C.2301}) do not match.
 */
public final class BackboneRoutes {
    private static final Set<String> PREFIXES = Set.of(
            "SMNW", "T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8", "T9", "APS", "BMT", "BNK", "CCN", "ESI", "IWL",
            "NSN", "NTH", "OLY", "SCO", "WST");

    private BackboneRoutes() {}

    public static boolean touches(Set<String> routeIds) {
        for (String r : routeIds) {
            if (matchesPrefix(r)) return true;
        }
        return false;
    }

    private static boolean matchesPrefix(String routeId) {
        for (String p : PREFIXES) {
            if (routeId.equals(p) || routeId.startsWith(p + "_")) return true;
        }
        return false;
    }
}
