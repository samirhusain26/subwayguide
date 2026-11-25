package nyc.subway.board;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared deterministic checks for independent route snapshots. */
public final class MultiRoutePolicy {
  private MultiRoutePolicy() {}

  public static boolean fresh(long now, long feedTime, String status) {
    long age = now - feedTime;
    return "ok".equals(status) && age >= 0 && age <= 60;
  }

  /** Blank train IDs stay blank so anonymous predictions can never acquire chime identity. */
  public static String qualifyId(String actualRoute, String id) {
    if (id == null || id.trim().isEmpty()) return "";
    String route = actualRoute == null ? "" : actualRoute.trim();
    return route.isEmpty() ? id.trim() : route + "/" + id.trim();
  }

  public static String identityKey(String actualRoute, String id) {
    return qualifyId(actualRoute, id);
  }

  /** Matches the server's asymmetric base-to-express acceptance rule. */
  public static boolean actualRouteAllowed(String selectedRoute, String actualRoute) {
    if (selectedRoute == null || actualRoute == null) return false;
    if (selectedRoute.equals(actualRoute)) return true;
    return ("6".equals(selectedRoute) && "6X".equals(actualRoute))
        || ("7".equals(selectedRoute) && "7X".equals(actualRoute))
        || ("F".equals(selectedRoute) && "FX".equals(actualRoute));
  }

  /** If the actual line is selected directly, its own snapshot owns the train identity. */
  public static boolean snapshotMaySupplyTrain(
      List<String> selectedRoutes, String sourceRoute, String actualRoute) {
    if (!actualRouteAllowed(sourceRoute, actualRoute)) return false;
    if (selectedRoutes == null || sourceRoute.equals(actualRoute)) return true;
    for (String route : selectedRoutes)
      if (actualRoute.equals(route)) return false;
    return true;
  }

  /** Retains route order while dropping services that cannot board in the selected direction. */
  public static List<String> compatibleRoutes(
      List<String> selected, Map<String, Set<String>> routeDirections, String direction) {
    List<String> result = new ArrayList<>();
    if (selected == null || routeDirections == null || direction == null) return result;
    Set<String> seen = new LinkedHashSet<>();
    for (String route : selected) {
      Set<String> directions = routeDirections.get(route);
      if (route != null && directions != null && directions.contains(direction) && seen.add(route))
        result.add(route);
    }
    return result;
  }
}
