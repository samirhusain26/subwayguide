package nyc.subway.board;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Plain Java assertions for route-specific freshness, direction and identity rules. */
public final class MultiRoutePolicyTest {
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    long now = 1_000_000L;
    check(MultiRoutePolicy.fresh(now, now - 60, "ok"), "60-second feed stays fresh");
    check(!MultiRoutePolicy.fresh(now, now - 61, "ok"), "stale feed is rejected independently");
    check(!MultiRoutePolicy.fresh(now, now + 1, "ok"), "future feed time is rejected");
    check(!MultiRoutePolicy.fresh(now, now, "unavailable"), "unavailable route cannot advise");

    Map<String, Set<String>> directions = new LinkedHashMap<>();
    directions.put("N", new LinkedHashSet<>(Arrays.asList("N", "S")));
    directions.put("W", new LinkedHashSet<>(Arrays.asList("N", "S")));
    directions.put("E", new LinkedHashSet<>(Collections.singletonList("N")));
    check(MultiRoutePolicy.compatibleRoutes(Arrays.asList("N", "W", "E"), directions, "N")
        .equals(Arrays.asList("N", "W", "E")), "keep every line boardable in selected direction");
    check(MultiRoutePolicy.compatibleRoutes(Arrays.asList("N", "W", "E"), directions, "S")
        .equals(Arrays.asList("N", "W")), "remove only the incompatible direction");

    check(MultiRoutePolicy.actualRouteAllowed("6", "6X")
        && !MultiRoutePolicy.actualRouteAllowed("6X", "6"),
        "base selection accepts express, express selection rejects base");
    check(MultiRoutePolicy.actualRouteAllowed("7", "7X")
        && !MultiRoutePolicy.actualRouteAllowed("7X", "7"), "7 express directionality");
    check(MultiRoutePolicy.actualRouteAllowed("F", "FX")
        && !MultiRoutePolicy.actualRouteAllowed("FX", "F"), "F express directionality");
    check(MultiRoutePolicy.snapshotMaySupplyTrain(Arrays.asList("F", "FX"), "FX", "FX"),
        "selected express snapshot owns express train identity");
    check(!MultiRoutePolicy.snapshotMaySupplyTrain(Arrays.asList("F", "FX"), "F", "FX"),
        "base-feed duplicate cannot revive an unavailable or unsafe selected express route");
    check(MultiRoutePolicy.snapshotMaySupplyTrain(Collections.singletonList("F"), "F", "FX"),
        "base route retains its existing express acceptance");

    check("".equals(MultiRoutePolicy.qualifyId("N", " ")),
        "anonymous train never receives synthetic identity");
    check("6X/train".equals(MultiRoutePolicy.identityKey("6X", "train")),
        "identity uses actual express route");
    check(MultiRoutePolicy.identityKey("6X", "train")
        .equals(MultiRoutePolicy.identityKey("6X", "train")),
        "overlapping copies of the same actual train can be deduplicated");
    check(!MultiRoutePolicy.identityKey("6", "train")
        .equals(MultiRoutePolicy.identityKey("6X", "train")),
        "different actual routes retain separate train identity");

    check(!MultiRoutePolicy.fresh(now, now - 61, "ok")
        && MultiRoutePolicy.fresh(now, now, "ok"),
        "a stale W snapshot cannot poison or refresh the independent N snapshot");
    check(MultiRoutePolicy.compatibleRoutes(Arrays.asList("N", "W"), directions, "N")
        .equals(Arrays.asList("N", "W")),
        "Union Square northbound N/W remains a valid shared direction");
    System.out.println("MultiRoutePolicyTest passed");
  }
}
