package nyc.subway.board;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** The current coverage of the lines requested by the rider. Reevaluate on every countdown tick. */
final class RouteCoverage {
  boolean fresh, ready;
  boolean allDisrupted = true;
  final List<String> considered = new ArrayList<>();
  final List<String> unavailable = new ArrayList<>(), pending = new ArrayList<>(),
      retired = new ArrayList<>(), suspended = new ArrayList<>();

  RouteCoverage(JSONArray lines, Map<String, JSONObject> snapshots, Set<String> rejected, long now) {
    for (int i = 0; i < lines.length(); i++) {
      String route = lines.optString(i);
      JSONObject row = snapshots.get(route);
      if (row != null && "disrupted".equals(row.optString("status"))) {
        suspended.add(route);
        continue;
      }
      allDisrupted = false;
      if (rejected.contains(route)) retired.add(route);
      else if (row == null || !MultiRoutePolicy.fresh(now, row.optLong("feedTime"), row.optString("status")))
        unavailable.add(route);
      else {
        fresh = true;
        if (row.optBoolean("noticesReady")) {
          ready = true;
          considered.add(route);
        }
        else pending.add(route);
      }
    }
    if (lines.length() == 0) allDisrupted = false;
  }

  String qualifier() {
    List<String> parts = new ArrayList<>();
    if (!retired.isEmpty()) parts.add(join(retired) + " selection unavailable");
    if (!unavailable.isEmpty()) parts.add(join(unavailable) + " times unavailable");
    if (!pending.isEmpty()) parts.add(join(pending) + " service unconfirmed");
    if (!suspended.isEmpty() && !allDisrupted) parts.add(join(suspended) + " boarding suspended");
    return android.text.TextUtils.join(" · ", parts);
  }

  private static String join(List<String> routes) {
    return android.text.TextUtils.join("/", routes);
  }
}
