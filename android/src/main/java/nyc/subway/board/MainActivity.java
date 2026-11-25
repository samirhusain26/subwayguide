package nyc.subway.board;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MainActivity extends Activity {
  private final Handler handler = new Handler();
  private final ExecutorService network = Executors.newFixedThreadPool(2);
  private final List<Decision.Train> trains = new ArrayList<>();
  private SharedPreferences prefs;
  private JSONObject config;
  private JSONArray stations, bundled, testedCatalog;
  private String testedOrigin;
  private JSONObject response;
  private BoardView board;
  private SettingsScreen editor;
  private ToneGenerator tone;
  private Decision previous;
  private long receivedAt, referenceTime, nextRequest;
  private int generation, catalogRequest, failures;
  private boolean resumed, pending, disconnected, invalidSelection, invalidData;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    prefs = getSharedPreferences("subway-board", MODE_PRIVATE);
    config = defaults();
    try {
      JSONObject saved = new JSONObject(prefs.getString("settings", "{}"));
      java.util.Iterator<String> keys = config.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if (saved.has(key)) config.put(key, saved.get(key));
      }
    } catch (JSONException ignored) {
      /* Safe defaults recover invalid old data. */
    }
    try (InputStream input = getAssets().open("stations.json")) {
      stations = new JSONObject(ServiceClient.read(input)).getJSONArray("stations");
      bundled = stations;
      String cached =
          config.optString("server").equals(prefs.getString("catalogOrigin", null))
              ? prefs.getString("catalog", null)
              : null;
      if (cached != null) {
        JSONArray newer = new JSONArray(cached);
        if (isDirectory(newer)) stations = newer;
      }
    } catch (Exception ex) {
      if (stations == null) stations = new JSONArray();
    }
    board = new BoardView(this, this::showSettings);
    setContentView(board);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    setVolumeControlStream(AudioManager.STREAM_NOTIFICATION);
  }

  private static boolean isDirectory(JSONArray list) {
    if (list == null || list.length() == 0) return false;
    Set<String> seen = new HashSet<>();
    int boardable = 0;
    for (int i = 0; i < list.length(); i++) {
      JSONObject s = list.optJSONObject(i);
      if (s == null
          || s.optString("id").isEmpty()
          || s.optString("name").isEmpty()
          || !seen.add(s.optString("id"))) return false;
      JSONArray routes = s.optJSONArray("routes");
      JSONObject labels = s.optJSONObject("directions");
      JSONObject served = s.optJSONObject("routeDirections");
      if (routes == null || labels == null || served == null) return false;
      for (int j = 0; j < routes.length(); j++) {
        JSONArray dirs = served.optJSONArray(routes.optString(j));
        if (dirs == null || dirs.length() == 0) return false;
        for (int k = 0; k < dirs.length(); k++) {
          String dir = dirs.optString(k);
          if (!(dir.equals("N") || dir.equals("S")) || labels.optString(dir).isEmpty())
            return false;
          boardable++;
        }
      }
    }
    return boardable > 0;
  }

  private JSONObject defaults() {
    JSONObject c = new JSONObject();
    try {
      c.put("server", "")
          .put("station", "116")
          .put("route", "1")
          .put("direction", "S")
          .put("walk", 10)
          .put("buffer", 0)
          .put("chime", false)
          .put("days", 31)
          .put("chimeRepeat", false)
          .put("start", 420)
          .put("end", 540)
          .put("reduceMotion", false)
          .put("dim", true);
    } catch (JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
    return c;
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    restart();
  }

  @Override
  protected void onPause() {
    resumed = false;
    generation++;
    handler.removeCallbacks(tick);
    previous = null;
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    catalogRequest++;
    network.shutdownNow();
    if (tone != null) tone.release();
    super.onDestroy();
  }

  @Override
  public void onWindowFocusChanged(boolean focus) {
    super.onWindowFocusChanged(focus);
    if (focus && editor == null) immersive(true);
  }

  private void immersive(boolean enabled) {
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            enabled
                ? View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                : View.SYSTEM_UI_FLAG_FULLSCREEN);
  }

  private void restart() {
    generation++;
    pending = false;
    previous = null;
    response = null;
    disconnected = false;
    invalidSelection = false;
    invalidData = false;
    failures = 0;
    trains.clear();
    nextRequest = 0;
    handler.removeCallbacks(tick);
    if (resumed && editor == null) tick.run();
  }

  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed || editor != null) return;
          if (!pending
              && !invalidSelection
              && SystemClock.elapsedRealtime() >= nextRequest) fetchBoard();
          render();
          handler.postDelayed(this, 1000);
        }
      };

  private void fetchBoard() {
    final int request = generation;
    final JSONObject selection = config;
    final long requestedAt = SystemClock.elapsedRealtime();
    pending = true;
    network.execute(
        () -> {
          JSONObject result = null;
          boolean badSelection = false;
          boolean malformed = false;
          try {
            JSONObject payload = selection.optString("server").isEmpty()
                ? LocalBoard.board(getApplicationContext(), selection.getString("station"),
                    selection.getString("route"), selection.getString("direction"))
                : ServiceClient.board(selection);
            validateBoard(payload);
            result = payload;
          } catch (JSONException e) {
            malformed = true;
          } catch (ServiceClient.UnknownSelection e) {
            badSelection = true;
          } catch (Exception | LinkageError ignored) {
            /* The next poll will retry. */
          }
          final boolean invalid = badSelection;
          final boolean invalidPayload = malformed;
          final JSONObject snapshot = result;
          handler.post(
              () -> {
                if (request != generation || !resumed) return;
                pending = false;
                invalidSelection = invalid;
                invalidData = invalidPayload;
                disconnected = snapshot == null;
                if (disconnected) {
                  previous = null;
                  failures = Math.min(failures + 1, 4);
                } else {
                  if (failures > 0) previous = null;
                  failures = 0;
                  response = snapshot;
                  // The embedded server shares Android's wall clock. For a remote clock,
                  // charge the full round trip so an old prediction never gains extra life.
                  if (selection.optString("server").isEmpty()) {
                    referenceTime = System.currentTimeMillis() / 1000;
                    receivedAt = SystemClock.elapsedRealtime();
                  } else {
                    referenceTime = snapshot.optLong("serverTime");
                    receivedAt = requestedAt;
                  }
                  trains.clear();
                  JSONArray items = snapshot.optJSONArray("arrivals");
                  for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject train = items.optJSONObject(i);
                    if (train != null && train.optLong("time") > 0)
                      trains.add(
                          new Decision.Train(
                              train.opt("id") instanceof String ? train.optString("id") : "",
                              train.optLong("time"),
                              train.isNull("destination") ? "" : train.optString("destination"),
                              train.optString("route"), train.optString("onward")));
                  }
                }
                long retryMs = failures == 0 ? 15000 : Math.min(60000, 5000L << (failures - 1));
                nextRequest = SystemClock.elapsedRealtime() + retryMs;
                render();
              });
        });
  }

  static void validateBoard(JSONObject payload) throws JSONException {
    String status = payload.getString("status");
    if (payload.getLong("serverTime") <= 0 ||
        !(status.equals("ok") || status.equals("stale") || status.equals("unavailable") || status.equals("disrupted")))
      throw new JSONException("Unknown board snapshot");
    if ("ok".equals(status) && payload.getLong("feedTime") <= 0)
      throw new JSONException("Missing live timestamp");
    JSONArray arrivals = payload.getJSONArray("arrivals");
    for (int i = 0; i < arrivals.length(); i++) {
      JSONObject arrival = arrivals.getJSONObject(i);
      if (arrival.getLong("time") <= 0) throw new JSONException("Invalid arrival");
    }
  }

  private JSONObject station() {
    String id = config.optString("station");
    for (int i = 0; i < stations.length(); i++) {
      JSONObject s = stations.optJSONObject(i);
      if (s != null && id.equals(s.optString("id"))) return s;
    }
    return new JSONObject();
  }

  private void render() {
    JSONObject s = station();
    String route = config.optString("route"), side = config.optString("direction");
    String name = s.optString("name", config.optString("station"));
    JSONObject labels = s.optJSONObject("directions");
    String direction = labels == null ? side : labels.optString(side, side);
    boolean reduced =
        config.optBoolean("reduceMotion")
            || Settings.Global.getFloat(
                    getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                == 0f;
    long now =
        response == null
            ? System.currentTimeMillis() / 1000
            : referenceTime + (SystemClock.elapsedRealtime() - receivedAt) / 1000;
    adjustBrightness(now);
    String headline, detail, next = "", weatherKind = "", weather = "", alert = "";
    Decision guidance = null;
    boolean onPhone = config.optString("server").isEmpty();
    if (invalidSelection) {
      headline = "Choose a trip";
      detail = "Open Trip and choose a station and direction from the updated directory.";
    } else if (invalidData) {
      headline = "Invalid board data";
      detail = "The service returned an unreadable update. Retrying automatically.";
    } else if (disconnected || response == null) {
      headline = disconnected ? "Service offline" : "Finding trains";
      if (onPhone)
        detail = disconnected ? "Check the phone’s Wi-Fi. Trying again automatically."
            : "Getting arrivals over Wi-Fi…";
      else
        detail = disconnected ? "Check Wi-Fi and the address in Connection. Trying again automatically."
            : "Connecting to your home server…";
    } else {
      Decision d =
          Decision.evaluate(
              now,
              response.optLong("feedTime", 0),
              response.optString("status"),
              trains,
              config.optInt("walk"),
              config.optInt("buffer"));
      headline = d.headline;
      detail = d.detail;
      next = d.next;
      if (!d.live && response.optLong("feedTime") > 0) {
        java.text.DateFormat format = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        detail =
            "Last train update "
                + format.format(new java.util.Date(response.optLong("feedTime") * 1000L));
      }
      JSONObject forecast = response.optJSONObject("weather");
      if (forecast != null && now < forecast.optLong("expiresAt", referenceTime + 60)) {
        weatherKind = forecast.optString("kind");
        switch (weatherKind) {
          case "sun": weather = "Sunny"; break;
          case "night": weather = "Clear night"; break;
          case "cloud": weather = "Cloudy"; break;
          case "rain": weather = "Rain possible"; break;
          case "storm": weather = "Storm possible"; break;
          case "snow": weather = "Snow possible"; break;
          case "fog": weather = "Fog"; break;
          default: break;
        }
        Object temperature = forecast.opt("temperature");
        String unit = forecast.optString("temperatureUnit");
        if (!weather.isEmpty() && temperature instanceof Number && ("F".equals(unit) || "C".equals(unit))) {
          double degrees = ((Number) temperature).doubleValue();
          double celsius = "F".equals(unit) ? (degrees - 32) * 5 / 9 : degrees;
          if (celsius >= -90 && celsius <= 60)
            weather = Math.round(degrees) + "°" + unit + "  " + weather;
        }
      }
      if (!response.isNull("alert")) alert = response.optString("alert");
      if ("disrupted".equals(response.optString("status"))) {
        headline = "Service change";
        detail = "Boarding is affected at this platform.";
        next = "";
      }
      boolean noticesReady = response.optBoolean("noticesReady", false);
      if (d.live && !noticesReady) {
        headline = "Checking service";
        detail = "Waiting for service notices before giving a leave time.";
      }
      if (noticesReady && d.live) {
        guidance = d;
        maybeChime(d, now);
      }
      previous = d.live && noticesReady ? d : null;
    }
    board.update(config.optString("station"), name, route, side, direction, headline, detail, next,
        weatherKind, weather, alert, reduced, guidance, now);
  }

  private void maybeChime(Decision current, long now) {
    if (!config.optBoolean("chime")) return;
    int stage = ChimePolicy.crossing(previous, current);
    if (stage == 0) return;
    String window = Decision.chimeWindow(now, config.optInt("days"), config.optInt("start"),
        config.optInt("end"));
    if (window == null) return;
    String target = config.optString("station") + "/" + config.optString("route") + "/"
        + config.optString("direction") + "/" + current.targetId;
    boolean sameWindow = window.equals(prefs.getString("lastWindow", ""));
    if (!config.optBoolean("chimeRepeat") && sameWindow
        && !target.equals(prefs.getString("soundOwner", ""))) return;
    Set<String> sent = new HashSet<>();
    if (sameWindow) sent.addAll(prefs.getStringSet("soundTargets", java.util.Collections.emptySet()));
    String reminder = stage + "/" + target;
    // If a late reminder played, an ETA correction must not play an earlier one.
    if (sent.contains(reminder) || sent.contains("30/" + target) || sent.contains(target)) return;
    if (!playChime()) return;
    sent.add(reminder);
    SharedPreferences.Editor played = prefs.edit()
        .putString("lastWindow", window).putStringSet("soundTargets", sent);
    if (!sameWindow) played.putString("soundOwner", target);
    played.apply();
  }

  private boolean playChime() {
    AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
    if (audio == null || audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL
        || audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0) return false;
    try {
      if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85);
      return tone.startTone(ToneGenerator.TONE_PROP_PROMPT, 200);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private void adjustBrightness(long now) {
    Calendar time = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
    time.setTimeInMillis(now * 1000L);
    int hour = time.get(Calendar.HOUR_OF_DAY);
    float level =
        editor == null && config.optBoolean("dim") && (hour >= 22 || hour < 6) ? .12f : -1f;
    WindowManager.LayoutParams p = getWindow().getAttributes();
    if (p.screenBrightness != level) {
      p.screenBrightness = level;
      getWindow().setAttributes(p);
    }
  }

  private void showSettings() {
    generation++;
    handler.removeCallbacks(tick);
    previous = null;
    testedOrigin = null;
    testedCatalog = null;
    editor =
        new SettingsScreen(
            this,
            stations,
            config,
            new SettingsScreen.Listener() {
              @Override
              public void testChime() {
                if (!playChime()) message("Check that sound is on and notification volume is above zero.");
              }

              @Override
              public void save(JSONObject settings) {
                try {
                  settings.put("server", ServiceClient.origin(settings.optString("server")));
                  String origin = settings.getString("server");
                  boolean useTested = testedCatalog != null && origin.equals(testedOrigin);
                  boolean changed = !origin.equals(config.optString("server"));
                  JSONArray effective = useTested ? testedCatalog : changed ? bundled : stations;
                  if (effective != null && !offersTrip(effective, settings)) {
                    editor.chooseTripFrom(effective);
                    message("Choose a line and direction offered by this source.");
                    return;
                  }
                  SharedPreferences.Editor save =
                      prefs.edit().putString("settings", settings.toString());
                  if (useTested)
                    save.putString("catalog", testedCatalog.toString())
                        .putString("catalogOrigin", origin);
                  else if (changed) save.remove("catalog").remove("catalogOrigin");
                  if (!save.commit())
                    throw new IllegalArgumentException("Settings could not be saved.");
                  if (useTested) stations = testedCatalog;
                  else if (changed && bundled != null) stations = bundled;
                  config = settings;
                  closeSettings();
                } catch (Exception e) {
                  message(e.getMessage());
                }
              }

              @Override
              public void cancel() {
                closeSettings();
              }

              @Override
              public void loadStations(String server) {
                refreshCatalog(server, false);
              }

              @Override
              public void changeSource(String address) {
                catalogRequest++;
                String origin;
                try {
                  origin = ServiceClient.origin(address);
                } catch (IllegalArgumentException editingAddress) {
                  origin = "";
                }
                boolean sourceKnown = !origin.isEmpty() || editor.hasSource("");
                JSONArray fallback = sourceKnown && origin.equals(config.optString("server"))
                    ? stations : bundled;
                if (sourceKnown && testedCatalog != null && origin.equals(testedOrigin)) fallback = testedCatalog;
                if (fallback != null) editor.setStations(fallback);
                if (editor.hasSource("") || !origin.isEmpty()) refreshCatalog(origin, true);
              }
            });
    immersive(false);
    adjustBrightness(System.currentTimeMillis() / 1000);
    setContentView(editor);
    String savedOrigin = config.optString("server");
    refreshCatalog(savedOrigin, true);
  }

  private void refreshCatalog(String server, boolean automatic) {
    final String origin;
    try {
      origin = ServiceClient.origin(server);
    } catch (IllegalArgumentException e) {
      message(e.getMessage());
      return;
    }
    int request = ++catalogRequest;
    network.execute(
        () -> {
          JSONArray data = null;
          try {
            JSONArray list = origin.isEmpty() ? LocalBoard.stations(getApplicationContext())
                : ServiceClient.get(origin + "/v1/stations").getJSONArray("stations");
            if (isDirectory(list)) data = list;
          } catch (Exception | LinkageError ignored) {
            /* Retain the bundled directory. */
          }
          final JSONArray list = data;
          handler.post(
              () -> {
                if (editor == null || request != catalogRequest || isDestroyed()) return;
                try {
                  if (!editor.hasSource(origin)) return;
                } catch (IllegalArgumentException editingAddress) {
                  return;
                }
                if (list == null) {
                  if (!automatic) message("Could not load the station directory. Check the connection and try again.");
                  return;
                }
                testedCatalog = list;
                testedOrigin = origin;
                if (!editor.showsDirectory(list)) editor.setStations(list);
                if (!automatic) Toast.makeText(
                        this, "Connected · " + list.length() + " stations", Toast.LENGTH_SHORT)
                    .show();
              });
        });
  }

  private static boolean offersTrip(JSONArray directory, JSONObject settings) {
    String id = settings.optString("station"), route = settings.optString("route");
    for (int i = 0; i < directory.length(); i++) {
      JSONObject s = directory.optJSONObject(i);
      if (s == null || !id.equals(s.optString("id"))) continue;
      JSONObject service = s.optJSONObject("routeDirections");
      JSONArray directions = service == null ? null : service.optJSONArray(route);
      for (int j = 0; directions != null && j < directions.length(); j++)
        if (settings.optString("direction").equals(directions.optString(j))) return true;
      return false;
    }
    return false;
  }

  private void message(String text) {
    new AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show();
  }

  private void closeSettings() {
    catalogRequest++;
    editor = null;
    testedCatalog = null;
    testedOrigin = null;
    setContentView(board);
    immersive(true);
    restart();
  }

  @Override
  public void onBackPressed() {
    if (editor != null) closeSettings();
    else super.onBackPressed();
  }
}
