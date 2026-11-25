package nyc.subway.board;

import android.content.Context;
import java.io.InputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Offline, service-specific direction evidence for trips whose platform compass labels differ. */
public final class TripChoices {
  private final JSONObject network;
  private final JSONObject overrides;

  public TripChoices(Context context) {
    JSONObject loaded;
    try {
      loaded = RouteMap.loadNetwork(name -> LocalBoard.openAsset(context, name));
    } catch (Exception unavailable) {
      loaded = new JSONObject();
    }
    JSONObject directionData = null;
    try (InputStream in = LocalBoard.openAsset(context, "trip_direction_overrides.json")) {
      directionData = new JSONObject(ServiceClient.read(in));
    } catch (Exception ignored) { }
    network = loaded;
    overrides = directionData;
  }

  public TripChoices(JSONObject evidence) {
    this.network = evidence == null ? new JSONObject() : evidence;
    this.overrides = this.network.optJSONObject("tripDirectionOverrides");
  }

  /** Migrates a legacy shared direction. Unresolved route directions are deliberately omitted. */
  public void migrate(JSONObject config, JSONObject station) {
    if (config == null || station == null) return;
    JSONArray selected = config.optJSONArray("routes");
    if (selected == null) selected = new JSONArray().put(config.optString("route"));
    String semantic = config.optString("tripDirection");
    if (semantic.isEmpty()) {
      semantic = config.optString("direction");
      JSONObject explicit = overrides == null ? null : overrides.optJSONObject(station.optString("id"));
      if (explicit != null) {
        JSONObject labels = station.optJSONObject("directions");
        String legacyLabel = labels == null ? "" : labels.optString(semantic);
        JSONObject aliases = explicit.optJSONObject("aliases");
        semantic = aliases == null ? legacyLabel : aliases.optString(legacyLabel, legacyLabel);
      }
    }
    try {
      config.put("directionsByRoute", directionsFor(station, semantic, selected));
      config.put("tripDirection", semantic);
    } catch (JSONException impossible) {
      throw new IllegalArgumentException("Invalid trip direction", impossible);
    }
  }

  public static String platformDirection(JSONObject config, String route) {
    JSONObject mapping = config == null ? null : config.optJSONObject("directionsByRoute");
    if (mapping != null) return mapping.optString(route, "");
    String selected = config == null ? "" : config.optString("route");
    return route.equals(selected) ? config.optString("direction") : "";
  }

  public static boolean acceptsDestination(JSONObject config, JSONObject arrival) {
    String id = config == null ? "" : config.optString("destinationId").trim();
    String name = config == null ? "" : config.optString("destination").trim();
    if (id.isEmpty() && name.isEmpty()) return true;
    if (arrival == null || arrival.optString("terminalId").isEmpty()
        || arrival.optString("terminalName").isEmpty()) return false;
    if (!id.isEmpty()) return id.equals(arrival.optString("terminalId"));
    return normalize(name).equals(normalize(arrival.optString("terminalName")));
  }

  public JSONArray namedDirections(JSONObject station) {
    JSONArray result = new JSONArray();
    if (station == null) return result;
    if (isReversalStation(station.optString("id"))
        && (overrides == null || overrides.optJSONObject(station.optString("id")) == null)) return result;
    JSONObject labels = station.optJSONObject("directions");
    JSONObject perStation = overrides == null ? null : overrides.optJSONObject(station.optString("id"));
    JSONObject named = perStation == null ? null : perStation.optJSONObject("directions");
    if (named != null) {
      java.util.List<String> ordered = new java.util.ArrayList<>();
      java.util.Iterator<String> it = named.keys();
      while (it.hasNext()) {
        String candidate = it.next();
        JSONObject mapping = named.optJSONObject(candidate);
        JSONArray routes = station.optJSONArray("routes");
        if (mapping != null && hasBoardableMapping(station, candidate, routes)) ordered.add(candidate);
      }
      java.util.Collections.sort(ordered);
      for (String candidate : ordered) result.put(candidate);
    } else if (labels != null) {
      JSONArray routes = station.optJSONArray("routes");
      for (String key : new String[] {"N", "S"})
        if (labels.has(key) && hasBoardableMapping(station, key, routes)) result.put(key);
    }
    return result;
  }

  private boolean hasBoardableMapping(JSONObject station, String named, JSONArray routes) {
    JSONObject map = directionsFor(station, named, routes);
    return map.length() > 0;
  }

  public String label(JSONObject station, String namedDirection) {
    JSONObject labels = station == null ? null : station.optJSONObject("directions");
    if (labels != null && labels.has(namedDirection)) return labels.optString(namedDirection);
    JSONObject perStation = overrides == null ? null : overrides.optJSONObject(station == null ? "" : station.optString("id"));
    JSONObject named = perStation == null ? null : perStation.optJSONObject("directions");
    JSONObject service = named == null ? null : named.optJSONObject(namedDirection);
    return service == null ? namedDirection : service.optString("label", namedDirection);
  }

  public JSONObject directionsFor(JSONObject station, String namedDirection, JSONArray routes) {
    JSONObject result = new JSONObject();
    if (station != null && isReversalStation(station.optString("id"))
        && (overrides == null || overrides.optJSONObject(station.optString("id")) == null)) return result;
    JSONObject explicit = overrides == null ? null : overrides.optJSONObject(station == null ? "" : station.optString("id"));
    JSONObject named = explicit == null ? null : explicit.optJSONObject("directions");
    JSONObject mappings = named == null ? null : named.optJSONObject(namedDirection);
    if (explicit != null && mappings == null) return result;
    for (int i = 0; routes != null && i < routes.length(); i++) {
      String route = routes.optString(i);
      String direction = mappings == null ? namedDirection : mappings.optString(route, "");
      JSONArray supported = station == null || station.optJSONObject("routeDirections") == null
          ? null : station.optJSONObject("routeDirections").optJSONArray(route);
      if (direction.isEmpty() && mappings == null && contains(supported, namedDirection)) direction = namedDirection;
      if (contains(supported, direction)) try { result.put(route, direction); } catch (JSONException ignored) { }
    }
    return result;
  }

  /** Scheduled terminal choices backed by ordered packaged stop patterns. */
  public JSONArray destinationsFor(JSONObject station, JSONObject config) {
    JSONArray result = new JSONArray();
    JSONObject patternsByRoute = network.optJSONObject("routes");
    JSONObject atlasStations = network.optJSONObject("stations");
    JSONArray routes = config == null ? null : config.optJSONArray("routes");
    if (routes == null && config != null) routes = new JSONArray().put(config.optString("route"));
    java.util.Map<String, String> seen = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (int r = 0; routes != null && r < routes.length(); r++) {
      String route = routes.optString(r);
      String direction = platformDirection(config, route);
      JSONArray platformStops = station == null ? null : station.optJSONArray("platforms");
      String platform = "";
      for (int p = 0; platformStops != null && p < platformStops.length(); p++) {
        JSONObject candidate = platformStops.optJSONObject(p);
        if (candidate != null && direction.equals(candidate.optString("direction"))) {
          platform = candidate.optString("stop_id");
          break;
        }
      }
      JSONArray patterns = patternsByRoute == null ? null : patternsByRoute.optJSONArray(route);
      for (int i = 0; patterns != null && i < patterns.length(); i++) {
        JSONArray pattern = patterns.optJSONArray(i);
        JSONArray stops = pattern == null ? null : pattern.optJSONArray(2);
        int origin = indexOf(stops, platform);
        if (pattern == null || pattern.length() < 3 || origin < 0 || origin >= stops.length() - 1) continue;
        String last = stops.optString(stops.length() - 1);
        String parentId = parent(last);
        String terminal = atlasStationName(atlasStations, parentId);
        if (!terminal.isEmpty()) seen.put(parentId, terminal);
      }
    }
    for (java.util.Map.Entry<String, String> terminal : seen.entrySet()) {
      JSONObject choice = new JSONObject();
      try { choice.put("terminalId", terminal.getKey()).put("terminalName", terminal.getValue()); }
      catch (JSONException ignored) { }
      result.put(choice);
    }
    return result;
  }

  public String onwardSummary(JSONObject station, JSONObject config) {
    JSONObject patternsByRoute = network.optJSONObject("routes");
    JSONObject atlasStations = network.optJSONObject("stations");
    JSONArray routes = config == null ? null : config.optJSONArray("routes");
    if (routes == null && config != null) routes = new JSONArray().put(config.optString("route"));
    java.util.List<String> examples = new java.util.ArrayList<>();
    for (int r = 0; routes != null && r < routes.length(); r++) {
      String route = routes.optString(r);
      String direction = platformDirection(config, route);
      String platform = platformId(station, direction);
      JSONArray patterns = patternsByRoute == null ? null : patternsByRoute.optJSONArray(route);
      java.util.Set<String> followingStops = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      for (int i = 0; patterns != null && i < patterns.length(); i++) {
        JSONArray pattern = patterns.optJSONArray(i);
        JSONArray stops = pattern == null ? null : pattern.optJSONArray(2);
        int at = indexOf(stops, platform);
        if (pattern != null && at >= 0 && at + 1 < stops.length())
          followingStops.add(atlasStationName(atlasStations, parent(stops.optString(at + 1))));
      }
      followingStops.remove("");
      if (!followingStops.isEmpty()) {
        java.util.List<String> shown = new java.util.ArrayList<>(followingStops);
        String nextText = shown.get(0);
        if (shown.size() > 1) nextText += " / " + shown.get(1);
        if (shown.size() > 2) nextText += " +" + (shown.size() - 2);
        examples.add((routes.length() > 1 ? BoardView.routeName(route) + ": " : "") + nextText);
      }
    }
    if (examples.isEmpty()) return "";
    return "Next · " + android.text.TextUtils.join("   ·   ", examples);
  }

  private static int indexOf(JSONArray values, String target) {
    for (int i = 0; values != null && i < values.length(); i++)
      if (target.equals(values.optString(i))) return i;
    return -1;
  }

  private static String parent(String stop) {
    return stop != null && stop.length() > 1 ? stop.substring(0, stop.length() - 1) : "";
  }

  private static String atlasStationName(JSONObject stations, String id) {
    JSONArray station = stations == null ? null : stations.optJSONArray(id);
    return station == null ? "" : station.optString(0).trim();
  }

  private static String platformId(JSONObject station, String direction) {
    JSONArray platformStops = station == null ? null : station.optJSONArray("platforms");
    for (int p = 0; platformStops != null && p < platformStops.length(); p++) {
      JSONObject candidate = platformStops.optJSONObject(p);
      if (candidate != null && direction.equals(candidate.optString("direction")))
        return candidate.optString("stop_id");
    }
    return "";
  }

  private static boolean contains(JSONArray a, String value) {
    for (int i = 0; a != null && i < a.length(); i++) if (value.equals(a.optString(i))) return true;
    return false;
  }

  private static boolean isReversalStation(String id) {
    return "M18".equals(id) || "M12".equals(id) || "M14".equals(id)
        || "M13".equals(id) || "M16".equals(id) || "M11".equals(id);
  }

  public boolean requiresSemanticDirection(JSONObject station) {
    return station != null && isReversalStation(station.optString("id"))
        && overrides != null && overrides.optJSONObject(station.optString("id")) != null;
  }

  private static String normalize(String text) {
    return text == null ? "" : text.toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
  }
}
