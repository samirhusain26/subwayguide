package nyc.subway.board;

import android.app.Activity;
import android.content.Context;
import android.app.AlertDialog;
import android.os.Looper;
import android.content.SharedPreferences;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.net.Uri;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import java.io.InputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MainActivity extends Activity {
  private final Handler handler = new Handler();
  private final ExecutorService network = Executors.newFixedThreadPool(2);
  private static final ExecutorService UPDATE_EXECUTOR = Executors.newSingleThreadExecutor();
  private static final Handler UPDATE_HANDLER = new Handler(Looper.getMainLooper());
  private static volatile MainActivity updateActivity;
  private static final AtomicBoolean UPDATE_DOWNLOADING = new AtomicBoolean();
  static final AtomicBoolean UPDATE_INSTALLING = new AtomicBoolean();
  private final Map<String, JSONObject> routeSnapshots = new LinkedHashMap<>();
  private final Set<String> rejectedRoutes = new HashSet<>();
  private Future<?> pollTask;
  private ServiceClient.Poll activePoll;
  private TripChoices tripChoices;
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
  private volatile int generation;
  private int catalogRequest, failures;
  private boolean resumed, pending, disconnected, invalidSelection, invalidData;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    prefs = getSharedPreferences("subway-board", MODE_PRIVATE);
    config = defaults();
    try {
      JSONObject saved = new JSONObject(prefs.getString("settings", "{}"));
      java.util.Iterator<String> keys = saved.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        config.put(key, saved.get(key));
      }
      if (!saved.has("routes")) config.put("routes", new JSONArray().put(config.optString("route", "1")));
    } catch (JSONException ignored) {
      /* Safe defaults recover invalid old data. */
    }
    try (InputStream input = LocalBoard.openAsset(this, "stations.json")) {
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
    tripChoices = new TripChoices(this);
    tripChoices.migrate(config, station());
    board = new BoardView(this, this::showSettings);
    setContentView(board);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    setVolumeControlStream(AudioManager.STREAM_NOTIFICATION);
    if (state != null && state.containsKey("settingsUi")) {
      try {
        showSettings(new JSONObject(state.getString("settingsUi")));
      } catch (JSONException ignored) { /* A corrupt draft never overwrites a saved trip. */ }
    }
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
          .put("routes", new JSONArray().put("1"))
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
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    if (editor != null) state.putString("settingsUi", editor.uiState().toString());
  }

  @Override
  protected void onResume() {
    super.onResume();
    updateActivity = this;
    resumed = true;
    restart();
    if (editor != null) refreshUpdateState();
  }

  @Override
  protected void onPause() {
    resumed = false;
    cancelPoll();
    handler.removeCallbacks(tick);
    previous = null;
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (updateActivity == this) updateActivity = null;
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

  private void cancelPoll() {
    generation++;
    if (pollTask != null) pollTask.cancel(true);
    if (activePoll != null) activePoll.cancel();
    activePoll = null;
    pollTask = null;
    pending = false;
  }

  private void restart() {
    cancelPoll();
    pending = false;
    previous = null;
    response = null;
    disconnected = false;
    invalidSelection = false;
    invalidData = false;
    failures = 0;
    routeSnapshots.clear();
    rejectedRoutes.clear();
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
    pending = true;
    final ServiceClient.Poll transport = new ServiceClient.Poll();
    activePoll = transport;
    pollTask = network.submit(
        () -> {
          Map<String, JSONObject> received = new LinkedHashMap<>();
          Set<String> rejected = new HashSet<>();
          boolean malformed = false;
          long lastResponseAt = 0;
          JSONObject latest = null;
          JSONArray routes = selectedRoutes(selection);
          for (int i = 0; i < routes.length(); i++) {
            if (request != generation || Thread.currentThread().isInterrupted()) return;
            String route = routes.optString(i);
            String platform = TripChoices.platformDirection(selection, route);
            if (platform.isEmpty()) {
              rejected.add(route);
              continue;
            }
            try {
              JSONObject payload = selection.optString("server").isEmpty()
                  ? LocalBoard.board(getApplicationContext(), selection.getString("station"), route, platform)
                  : ServiceClient.board(selection, route, platform, transport);
              long receipt = SystemClock.elapsedRealtime();
              validateBoard(payload);
              received.put(route, payload);
              lastResponseAt = receipt;
              latest = payload;
            } catch (JSONException e) {
              malformed = true;
            } catch (ServiceClient.UnknownSelection e) {
              rejected.add(route);
            } catch (Exception | LinkageError ignored) {
              // A temporary failure remains eligible for the next poll.
            }
          }
          final boolean invalid = routes.length() == 0 || rejected.size() == routes.length();
          final boolean invalidPayload = malformed && received.isEmpty();
          final long receipt = lastResponseAt;
          final JSONObject last = latest;
          handler.post(
              () -> {
                if (request != generation || !resumed) return;
                pending = false;
                invalidSelection = invalid;
                invalidData = invalidPayload;
                disconnected = received.isEmpty();
                rejectedRoutes.clear();
                rejectedRoutes.addAll(rejected);
                routeSnapshots.clear();
                routeSnapshots.putAll(received);
                if (disconnected) {
                  previous = null;
                  response = null;
                  failures = Math.min(failures + 1, 4);
                } else {
                  if (failures > 0) previous = null;
                  failures = 0;
                  response = last;
                  referenceTime = last.optLong("serverTime");
                  receivedAt = receipt;
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

  private static JSONArray selectedRoutes(JSONObject settings) {
    JSONArray routes = settings.optJSONArray("routes");
    if (routes != null && routes.length() > 0) return routes;
    JSONArray legacy = new JSONArray();
    if (!settings.optString("route").isEmpty()) legacy.put(settings.optString("route"));
    return legacy;
  }

  private List<Decision.Train> trainsFor(long now, boolean adviceOnly) {
    List<Decision.Train> result = new ArrayList<>();
    Set<String> identities = new HashSet<>();
    List<String> selected = new ArrayList<>();
    JSONArray selectedArray = selectedRoutes(config);
    for (int i = 0; i < selectedArray.length(); i++) selected.add(selectedArray.optString(i));
    for (Map.Entry<String, JSONObject> snapshot : routeSnapshots.entrySet()) {
      JSONObject payload = snapshot.getValue();
      String status = payload.optString("status");
      if (!MultiRoutePolicy.fresh(now, payload.optLong("feedTime"), status)
          || "disrupted".equals(status)
          || (adviceOnly && !payload.optBoolean("noticesReady", false))) continue;
      JSONArray arrivals = payload.optJSONArray("arrivals");
      for (int i = 0; arrivals != null && i < arrivals.length(); i++) {
        JSONObject train = arrivals.optJSONObject(i);
        if (train == null || train.optLong("time") <= 0) continue;
        if (train.has("validUntil") && now > train.optLong("validUntil", 0)) continue;
        if (!TripChoices.acceptsDestination(config, train)) continue;
        String rawId = train.opt("id") instanceof String ? train.optString("id") : "";
        String actualRoute = train.isNull("route") ? "" : train.optString("route", "");
        if (actualRoute.isEmpty()) actualRoute = snapshot.getKey();
        if (!MultiRoutePolicy.snapshotMaySupplyTrain(selected, snapshot.getKey(), actualRoute)) continue;
        if (adviceOnly && !actualRoute.equals(snapshot.getKey())
            && !payload.optBoolean("actualRouteNoticesChecked")) continue;
        String key = MultiRoutePolicy.identityKey(actualRoute, rawId);
        if (!rawId.trim().isEmpty() && !identities.add(key)) continue;
        result.add(new Decision.Train(rawId, train.optLong("time"),
            train.isNull("destination") ? "" : train.optString("destination"),
            actualRoute, train.optString("onward"), downstream(train.optJSONArray("onwardStops")),
            train.isNull("terminalId") ? "" : train.optString("terminalId")));
      }
    }
    return result;
  }

  private static List<String> downstream(JSONArray ids) {
    if (ids == null) return java.util.Collections.emptyList();
    List<String> stops = new ArrayList<>();
    for (int i = 0; i < ids.length(); i++)
      if (ids.opt(i) instanceof String) stops.add(ids.optString(i));
    return stops;
  }

  private String combinedAlert() {
    StringBuilder value = new StringBuilder();
    Set<String> seen = new HashSet<>();
    for (JSONObject payload : routeSnapshots.values()) {
      if (payload.isNull("alert")) continue;
      String alert = payload.optString("alert").trim();
      if (!alert.isEmpty() && seen.add(alert)) {
        if (value.length() > 0) value.append(" · ");
        value.append(alert);
      }
    }
    return value.toString();
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
    String direction = tripChoices.label(s, config.optString("tripDirection", side));
    JSONArray lines = selectedRoutes(config);
    if (lines.length() > 1) {
      StringBuilder subtitle = new StringBuilder(direction).append(" · ");
      for (int i = 0; i < lines.length(); i++) {
        if (i > 0) subtitle.append(" / ");
        subtitle.append(BoardView.routeName(lines.optString(i)));
      }
      direction = subtitle.toString();
    }
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
    String qualifier = "", platformArrivals = "", consideredLines = "";
    boolean onPhone = config.optString("server").isEmpty();
    if (invalidSelection) {
      headline = "Choose a trip";
      detail = "Open Trip and choose a station and direction from the updated directory.";
    } else if (invalidData) {
      headline = "Invalid board data";
      detail = "The service returned an unreadable update. Retrying automatically.";
    } else if (disconnected || response == null) {
      headline = disconnected ? "Service offline" : "Finding trains";
      if (disconnected && lines.length() > 1)
        qualifier = new RouteCoverage(lines, routeSnapshots, rejectedRoutes, now).qualifier();
      if (onPhone)
        detail = disconnected ? "Check the phone’s Wi-Fi. Trying again automatically."
            : "Getting arrivals over Wi-Fi…";
      else
        detail = disconnected ? "Check Wi-Fi and the address in Connection. Trying again automatically."
            : "Connecting to your home server…";
    } else {
      RouteCoverage coverage = new RouteCoverage(lines, routeSnapshots, rejectedRoutes, now);
      boolean fresh = coverage.fresh;
      boolean noticesReady = coverage.ready;
      qualifier = coverage.qualifier();
      consideredLines = android.text.TextUtils.join(", ", coverage.considered);
      List<Decision.Train> candidates = trainsFor(now, noticesReady);
      Decision d =
          Decision.evaluate(
              now,
              fresh ? now : response.optLong("feedTime", 0),
              fresh ? "ok" : response.optString("status"),
              candidates,
              config.optInt("walk"),
              config.optInt("buffer"));
      headline = d.headline;
      detail = d.detail;
      next = d.next;
      boolean unconfirmedExpress = false;
      if (fresh && (!noticesReady || d.targetTime == 0)) {
        List<Decision.Train> platform = trainsFor(now, false);
        Decision display = Decision.evaluate(now, now, "ok", platform,
            config.optInt("walk"), config.optInt("buffer"));
        platformArrivals = display.next;
        next = platformArrivals;
        unconfirmedExpress = noticesReady && coverage.pending.isEmpty() && display.targetTime > 0;
      }
      if (!d.live && response.optLong("feedTime") > 0) {
        java.text.DateFormat format = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        detail =
            "Last train update "
                + format.format(new java.util.Date(response.optLong("feedTime") * 1000L));
      }
      JSONObject forecast = response.optJSONObject("weather");
      if (forecast == null) {
        for (JSONObject payload : routeSnapshots.values()) {
          forecast = payload.optJSONObject("weather");
          if (forecast != null) break;
        }
      }
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
      alert = combinedAlert();
      if (!noticesReady && coverage.allDisrupted) {
        headline = "Service change";
        detail = "Boarding is affected at this platform.";
        next = "";
      }
      boolean awaitingNotices = !noticesReady || unconfirmedExpress || (!coverage.pending.isEmpty() && d.targetTime == 0);
      if (d.live && awaitingNotices) {
        headline = "Checking service";
        detail = unconfirmedExpress ? "Update the home server to confirm express service."
            : "Waiting for service notices before giving a leave time.";
      }
      if (d.live && d.targetTime == 0 && !qualifier.isEmpty() && headline.equals("No upcoming trains"))
        headline = "No confirmed trains";
      String destination = config.optString("destination").trim();
      if (!destination.isEmpty() && d.live && (headline.equals("No upcoming trains") || headline.equals("No confirmed trains"))) {
        headline = "No confirmed trains";
        boolean reportsTerminals = false;
        for (JSONObject snapshot : routeSnapshots.values())
          reportsTerminals |= snapshot.optBoolean("terminalEvidenceSupported");
        detail = onPhone || reportsTerminals ? "Waiting for a train confirmed to " + destination + "."
            : "Update the home server to confirm trains to " + destination + ".";
      }
      if (d.live && !awaitingNotices) {
        guidance = d;
        maybeChime(d, now);
      }
      previous = guidance;
    }
    List<String> onwardStops = java.util.Collections.emptyList();
    if (guidance != null && guidance.primary != null) {
      onwardStops = guidance.primary.onwardStops;
      String platform = boardingSide(guidance.primary.route);
      if (!platform.isEmpty()) side = platform;
    } else {
      String platform = TripChoices.platformDirection(config, route);
      if (!platform.isEmpty()) side = platform;
    }
    board.updateContext(qualifier, platformArrivals, config.optInt("walk"), config.optInt("buffer"), onwardStops, consideredLines);
    board.update(config.optString("station"), name, route, side, direction, headline, detail, next,
        weatherKind, weather, alert, reduced, guidance, now);
  }

  private String boardingSide(String actualRoute) {
    String platform = TripChoices.platformDirection(config, actualRoute);
    if (platform.isEmpty() && actualRoute.endsWith("X"))
      platform = TripChoices.platformDirection(config, actualRoute.substring(0, actualRoute.length() - 1));
    return platform;
  }

  private void maybeChime(Decision current, long now) {
    if (!config.optBoolean("chime")) return;
    int stage = ChimePolicy.crossing(previous, current);
    if (stage == 0) return;
    String window = Decision.chimeWindow(now, config.optInt("days"), config.optInt("start"),
        config.optInt("end"));
    if (window == null) return;
    String target = config.optString("station") + "/" + current.targetRoute + "/"
        + boardingSide(current.targetRoute) + "/" + current.targetId;
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
    showSettings(null);
  }

  private void showSettings(JSONObject savedUi) {
    cancelPoll();
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

              @Override
              public void downloadUpdate() {
                startUpdateDownload();
              }

              @Override
              public void installUpdate() {
                beginUpdateInstall();
              }
            }, savedUi);
    immersive(false);
    adjustBrightness(System.currentTimeMillis() / 1000);
    setContentView(editor);
    refreshUpdateState();
    try {
      refreshCatalog(editor.draft().optString("server"), true);
    } catch (IllegalArgumentException ignored) { /* The rider can finish editing the address. */ }
  }

  private File updateApk() {
    return new File(new File(getFilesDir(), "update"), "SubwayBoard.apk");
  }

  private void refreshUpdateState() {
    if (editor == null) return;
    if (UPDATE_DOWNLOADING.get()) {
      editor.setUpdateState("Downloading the latest update…", false, true);
      return;
    }
    if (UpdateInstaller.active(this)) {
      editor.setUpdateState("Android is processing the update. Finish or cancel the installer before trying again.", false, true);
      return;
    }
    File apk = updateApk();
    if (!apk.isFile() || apk.length() == 0) {
      editor.setUpdateState("Check for and download the latest signed release.", false);
      return;
    }
    UPDATE_EXECUTOR.execute(() -> {
      String message;
      boolean ready;
      try {
        android.content.pm.PackageInfo candidate = UpdateInstaller.validate(getApplicationContext(), apk);
        ready = true;
        message = "Version " + UpdateInstaller.label(candidate) + " is ready to install.";
      } catch (Exception invalid) {
        ready = false;
        if (invalid instanceof IllegalArgumentException || invalid instanceof SecurityException) apk.delete();
        message = invalid.getMessage() == null ? "Download the latest signed release." : invalid.getMessage();
      }
      final String status = message;
      final boolean installable = ready;
      UPDATE_HANDLER.post(() -> {
        MainActivity current = updateActivity;
        if (current != null && current.editor != null && !current.isDestroyed()
            && !UPDATE_DOWNLOADING.get() && !UpdateInstaller.active(current))
          current.editor.setUpdateState(status, installable);
      });
    });
  }

  private void startUpdateDownload() {
    if (UpdateInstaller.active(this)) {
      if (editor != null) editor.setUpdateState("Android is processing the update. Finish or cancel the installer before trying again.", false, true);
      return;
    }
    if (!UPDATE_DOWNLOADING.compareAndSet(false, true)) {
      if (editor != null) editor.setUpdateState("The update download is already in progress.", false, true);
      return;
    }
    if (editor != null) editor.setUpdateState("Downloading the latest update…", false, true);
    final Context app = getApplicationContext();
    final File destination = updateApk();
    UPDATE_EXECUTOR.execute(() -> {
      String status;
      boolean ready = false;
      File incoming = new File(destination.getParentFile(), "candidate.apk");
      try {
        LocalBoard.downloadUpdate(app, incoming);
        android.content.pm.PackageInfo candidate = UpdateInstaller.promote(app, incoming, destination);
        ready = true;
        status = "Version " + UpdateInstaller.label(candidate) + " is ready to install.";
      } catch (Exception failure) {
        status = failure.getMessage() == null ? "The update could not be downloaded. Try again." : failure.getMessage();
        try {
          android.content.pm.PackageInfo cached = UpdateInstaller.validate(app, destination);
          status += " Saved version " + UpdateInstaller.label(cached) + " is still available.";
          ready = true;
        } catch (Exception noValidCache) { /* Leave the download action available. */ }
      } finally {
        incoming.delete();
        UPDATE_DOWNLOADING.set(false);
      }
      final String message = status;
      final boolean installable = ready;
      UPDATE_HANDLER.post(() -> {
        MainActivity current = updateActivity;
        if (current != null && current.editor != null && !current.isDestroyed())
          current.editor.setUpdateState(message, installable);
      });
    });
  }

  private void beginUpdateInstall() {
    if (UPDATE_DOWNLOADING.get()) {
      if (editor != null) editor.setUpdateState("Wait for the download to finish before installing.", false, true);
      return;
    }
    if (android.os.Build.VERSION.SDK_INT < 26
        && android.provider.Settings.Secure.getInt(getContentResolver(),
            Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 0) {
      try {
        startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        if (editor != null) editor.setUpdateState(
            "Enable Settings → Security → Unknown sources, return here, then tap Install update.", true);
      } catch (Exception unavailable) {
        if (editor != null) editor.setUpdateState(
            "Enable Settings → Security → Unknown sources, then return and tap Install update.", true);
      }
      return;
    }
    final File apk = updateApk();
    if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
      try {
        Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + getPackageName()));
        startActivity(permission);
        if (editor != null) editor.setUpdateState("Allow Subway Board to install updates, return here, then tap Install update.", true);
      } catch (Exception unavailable) {
        if (editor != null) editor.setUpdateState("Open Android Settings and allow installs from Subway Board, then return and tap Install update.", true);
      }
      return;
    }
    if (UpdateInstaller.active(this) || !UPDATE_INSTALLING.compareAndSet(false, true)) {
      if (editor != null) editor.setUpdateState("Android is already processing this update.", false, true);
      return;
    }
    final SettingsScreen target = editor;
    if (target != null) target.setUpdateState("Preparing Android's installer…", false, true);
    final Context app = getApplicationContext();
    UPDATE_EXECUTOR.execute(() -> {
      String error = null;
      try {
        UpdateInstaller.install(app, apk, apk.length());
      } catch (Exception failure) {
        error = failure.getMessage() == null ? "The update could not be prepared. Try again." : failure.getMessage();
      } finally {
        UPDATE_INSTALLING.set(false);
      }
      final String message = error;
      UPDATE_HANDLER.post(() -> {
        MainActivity current = updateActivity;
        if (current == null || current.editor == null || current.isDestroyed()) return;
        if (message == null) current.editor.setUpdateState("Android's installer is ready. Follow its prompts to finish.", false, true);
        else current.editor.setUpdateState(message, true, false);
      });
    });
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
    String id = settings.optString("station");
    JSONArray routes = selectedRoutes(settings);
    if (routes.length() == 0) return false;
    for (int i = 0; i < directory.length(); i++) {
      JSONObject s = directory.optJSONObject(i);
      if (s == null || !id.equals(s.optString("id"))) continue;
      JSONObject service = s.optJSONObject("routeDirections");
      for (int r = 0; r < routes.length(); r++) {
        JSONArray directions = service == null ? null : service.optJSONArray(routes.optString(r));
        boolean served = false;
        for (int j = 0; directions != null && j < directions.length(); j++)
          served |= TripChoices.platformDirection(settings, routes.optString(r)).equals(directions.optString(j));
        if (!served) return false;
      }
      return true;
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
