package nyc.subway.board;

import android.content.Context;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-only stand-in at the Android native-runtime boundary. Never included in the APK. */
final class LocalBoard {
  static volatile String lastTrip;

  static JSONObject board(Context context, String station, String route, String direction)
      throws Exception {
    lastTrip = station + "/" + route + "/" + direction;
    long now = System.currentTimeMillis() / 1000;
    JSONObject train = new JSONObject().put("id", "local-sample").put("time", now + 805)
        .put("destination", "South Ferry");
    return new JSONObject().put("status", "ok").put("serverTime", now).put("feedTime", now)
        .put("noticesReady", true).put("arrivals", new JSONArray().put(train));
  }

  static JSONArray stations(Context context) throws Exception {
    return new JSONObject(Files.readString(Path.of(System.getProperty("catalogFile"))))
        .getJSONArray("stations");
  }

  static long downloadUpdate(Context context, File destination) throws Exception {
    throw new UnsupportedOperationException("Update download is exercised only on Android runtime.");
  }
}
