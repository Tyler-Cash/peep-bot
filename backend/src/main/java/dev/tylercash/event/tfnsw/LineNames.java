package dev.tylercash.event.tfnsw;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Hand-listed display names for the same route prefixes recognised by
 * {@link BackboneRoutes}. Lookup matches either an exact equality on the
 * prefix or {@code prefix + "_"}, so {@code T10} would never collide with
 * {@code T1}.
 *
 * <p>Unknown route ids return {@link Optional#empty()} — the reporter omits
 * them from the bullet list entirely, so adding a new backbone prefix without
 * a name here is a silent drop until both files are updated.
 */
public final class LineNames {
    private static final Map<String, String> PREFIX_TO_NAME;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("SMNW", "Sydney Metro Northwest");
        m.put("T1", "T1 Western");
        m.put("T2", "T2 Inner West & Leppington");
        m.put("T3", "T3 Liverpool & Inner West");
        m.put("T4", "T4 Eastern Suburbs & Illawarra");
        m.put("T5", "T5 Cumberland");
        m.put("T6", "T6 Lidcombe & Bankstown");
        m.put("T7", "T7 Olympic Park");
        m.put("T8", "T8 Airport & South");
        m.put("T9", "T9 Northern");
        m.put("APS", "Airport & South Line");
        m.put("BMT", "Blue Mountains Line");
        m.put("BNK", "Bankstown Line");
        m.put("CCN", "Central Coast & Newcastle Line");
        m.put("ESI", "Eastern Suburbs & Illawarra Line");
        m.put("IWL", "Inner West & Leppington Line");
        m.put("NSN", "North Shore & Northern Line");
        m.put("NTH", "Northern Line");
        m.put("OLY", "Olympic Park Line");
        m.put("SCO", "South Coast Line");
        m.put("WST", "Western Line");
        PREFIX_TO_NAME = Map.copyOf(m);
    }

    private LineNames() {}

    public static Optional<String> lookup(String routeId) {
        for (Map.Entry<String, String> e : PREFIX_TO_NAME.entrySet()) {
            String p = e.getKey();
            if (routeId.equals(p) || routeId.startsWith(p + "_")) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }

    /** Distinct human names for the given route ids, sorted alphabetically. */
    public static Set<String> distinctNames(Set<String> routeIds) {
        return routeIds.stream()
                .map(LineNames::lookup)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
