package nyc.subway.board;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowToneGenerator;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, qualifiers = "w640dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppFlowTest {
  private static final long MORNING = Instant.parse("2026-11-24T13:00:00Z").getEpochSecond();

  private final AtomicReference<String> served = new AtomicReference<>();

  @Test public void recreationKeepsUncommittedWalkAndEditorSection() throws Exception {
    ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
    MainActivity app = controller.get();
    app.setTheme(android.R.style.Theme_Material_NoActionBar);
    controller.setup();
    try {
      View root = app.findViewById(android.R.id.content);
      HostUiTest.named(root, "Settings").performClick();
      HostUiTest.named(root, "Increase Walk to the platform").performClick();
      HostUiTest.named(root, "Sound").performClick();
      controller.recreate();
      View restoredRoot = controller.get().findViewById(android.R.id.content);
      SettingsScreen restored = HostUiTest.first(restoredRoot, SettingsScreen.class);
      assertEquals(2, restored.uiState().getInt("section"));
      assertEquals(11, restored.draft().getInt("walk"));
      HostUiTest.named(restoredRoot, "‹   Cancel").performClick();
      HostUiTest.named(restoredRoot, "Settings").performClick();
      assertEquals(10, HostUiTest.first(restoredRoot, SettingsScreen.class).draft().getInt("walk"));
    } finally {
      controller.pause().stop().destroy();
    }
  }

  @Test
  public void combinesUnionSquareLinesAndKeepsTheSelectionAfterRestart() throws Exception {
    AtomicReference<String> mode = new AtomicReference<>("both");
    java.util.Set<String> requested = java.util.concurrent.ConcurrentHashMap.newKeySet();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/stations",
        exchange -> write(exchange, Files.readAllBytes(Paths.get(System.getProperty("catalogFile")))));
    server.createContext("/v1/board", exchange -> {
      android.net.Uri query = android.net.Uri.parse(exchange.getRequestURI().toString());
      String route = query.getQueryParameter("route");
      requested.add(query.getQueryParameter("station") + "/" + route + "/" + query.getQueryParameter("direction"));
      try {
        boolean isW = "W".equals(route);
        if (mode.get().equals("W retired") && isW || mode.get().equals("mixed failure")) {
          int code = isW ? 400 : 503;
          exchange.sendResponseHeaders(code, -1);
          exchange.close();
          served.set(mode.get());
          return;
        }
        boolean suspended = isW && "W suspended".equals(mode.get());
        org.json.JSONArray arrivals = new org.json.JSONArray();
        if (!suspended) {
          if (!isW) arrivals.put(new JSONObject().put("id", "early")
              .put("time", MORNING + 240).put("route", route));
          arrivals.put(new JSONObject().put("id", "trip-" + route).put("route", route)
              .put("destination", isW ? "Whitehall St" : "Coney Island")
              .put("time", MORNING + (isW ? 805 : 1050)));
        }
        if (!isW && mode.get().equals("notices pending")) arrivals = new org.json.JSONArray();
        if (isW && mode.get().equals("expiring movement"))
          for (int i = 0; i < arrivals.length(); i++) arrivals.getJSONObject(i).put("validUntil", MORNING + 5);
        long feedTime = isW && "W aging".equals(mode.get()) ? MORNING - 52 : MORNING;
        JSONObject payload = new JSONObject().put("status", suspended ? "disrupted" : "ok")
            .put("serverTime", MORNING).put("feedTime", feedTime)
            .put("noticesReady", !(isW && mode.get().equals("notices pending"))).put("arrivals", arrivals);
        payload.put("alert", suspended ? "W boarding suspended at Union Square" : JSONObject.NULL);
        write(exchange, payload.toString().getBytes(StandardCharsets.UTF_8));
        served.set(mode.get());
      } catch (org.json.JSONException e) {
        throw new AssertionError(e);
      }
    });
    server.start();
    ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
    try {
      MainActivity app = controller.get();
      app.setTheme(android.R.style.Theme_Material_NoActionBar);
      // Begin with preferences from the single-line app.
      JSONObject oldTrip = new JSONObject().put("station", "R20").put("route", "N")
          .put("direction", "S").put("server", "http://127.0.0.1:" + server.getAddress().getPort());
      app.getSharedPreferences("subway-board", MainActivity.MODE_PRIVATE).edit()
          .putString("settings", oldTrip.toString()).commit();
      controller.setup();
      View initial = app.findViewById(android.R.id.content);
      until(() -> boardSays(initial, "Leave in 7 min"));
      HostUiTest.named(initial, "Settings").performClick();
      until(() -> boardSays(initial, "N, selected"));
      assertTrue(HostUiTest.named(initial, "N, selected").isSelected());
      HostUiTest.named(initial, "W").performClick();
      HostUiTest.named(initial, "Save").performClick();
      until(() -> boardSays(initial, "Leave in 3 min") && boardSays(initial, "Whitehall St"));
      assertFalse(boardSays(initial, "Service notice: null"));
      assertEquals(java.util.Set.of("R20/N/S", "R20/W/S"), requested);

      controller.recreate();
      View reopened = controller.get().findViewById(android.R.id.content);
      until(() -> boardSays(reopened, "Leave in 3 min") && boardSays(reopened, "Coney Island"));
      poll(mode, "W suspended", reopened, "Leave in 7 min");
      assertTrue(boardSays(reopened, "W boarding suspended"));
      assertFalse(boardSays(reopened, "Whitehall St"));
      poll(mode, "W aging", reopened, "Leave in 3 min");
      // Allow a full board tick after the 60-second cutoff, ahead of the 15-second repoll.
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
      BoardView board = HostUiTest.first(reopened, BoardView.class);
      assertTrue(board.getChildAt(0).getContentDescription().toString(), boardSays(reopened, "Leave in 7 min"));
      assertFalse(boardSays(reopened, "Whitehall St"));
      assertTrue(boardSays(reopened, "W times unavailable"));
      saveBoard(board, "board-partial-lines");
      poll(mode, "notices pending", reopened, "Checking service");
      assertTrue(boardSays(reopened, "Whitehall St"));
      assertFalse(boardSays(reopened, "Leave in"));
      poll(mode, "expiring movement", reopened, "Whitehall St");
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7));
      assertTrue(boardSays(reopened, "Leave in 7 min"));
      assertFalse(boardSays(reopened, "Whitehall St"));
      poll(mode, "mixed failure", reopened, "Service offline");
      poll(mode, "W retired", reopened, "Leave in 7 min");
      assertTrue(boardSays(reopened, "W selection unavailable"));
      assertEquals(java.util.Set.of("R20/N/S", "R20/W/S"), requested);
    } finally {
      controller.pause().stop().destroy();
      server.stop(0);
    }
  }

  @Test public void marcyLegacyTripRequestsEachPlatformAndWaitsForServerResponse() throws Exception {
    java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    java.util.Set<String> requests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/board", exchange -> {
      android.net.Uri query = android.net.Uri.parse(exchange.getRequestURI().toString());
      String route = query.getQueryParameter("route");
      requests.add(route + "/" + query.getQueryParameter("direction"));
      try {
        if (route.equals("J")) {
          started.countDown();
          if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("fixture timeout");
        }
        JSONObject train = new JSONObject().put("id", route).put("route", route)
            .put("time", MORNING + (route.equals("M") ? 790 : 780))
            .put("destination", route.equals("J") ? "Broad St" : "Forest Hills-71 Av")
            .put("terminalName", route.equals("J") ? "Broad St" : "Forest Hills-71 Av")
            .put("terminalId", route.equals("J") ? "M23" : "G08");
        JSONObject payload = new JSONObject().put("status", "ok").put("serverTime", MORNING)
            .put("feedTime", MORNING - 55).put("noticesReady", true)
            .put("arrivals", new org.json.JSONArray().put(train));
        write(exchange, payload.toString().getBytes(StandardCharsets.UTF_8));
      } catch (org.json.JSONException | InterruptedException e) { throw new IOException(e); }
    });
    server.start();
    ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
    try {
      MainActivity app = controller.get();
      JSONObject old = new JSONObject().put("station", "M16").put("route", "J")
          .put("routes", new org.json.JSONArray().put("J").put("M")).put("direction", "S")
          .put("destination", "Forest Hills-71 Av")
          .put("server", "http://127.0.0.1:" + server.getAddress().getPort());
      app.getSharedPreferences("subway-board", 0).edit().putString("settings", old.toString()).commit();
      app.setTheme(android.R.style.Theme_Material_NoActionBar);
      controller.setup();
      assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));
      release.countDown();
      View content = app.findViewById(android.R.id.content);
      until(() -> boardSays(content, "Forest Hills-71 Av") && boardSays(content, "Leave in 3 min"));
      assertFalse(boardSays(content, "Broad St"));
      assertEquals(java.util.Set.of("J/S", "M/N"), requests);
    } finally {
      release.countDown();
      controller.pause().stop().destroy();
      server.stop(0);
    }
  }

  @Test
  public void configureServiceAndFollowACommute() throws Exception {
    AtomicReference<String> mode = new AtomicReference<>("initial");
    AtomicReference<String> selection = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/stations",
        exchange ->
            write(exchange, Files.readAllBytes(Paths.get(System.getProperty("catalogFile")))));
    server.createContext(
        "/v1/board",
        exchange -> {
          selection.set(exchange.getRequestURI().getQuery());
          String state = mode.get();
          String id =
              state.startsWith("anonymous")
                  ? ""
                  : (state.startsWith("second") ? "second" : "first");
          long feed = state.equals("stale") ? MORNING - 75 : MORNING;
          long target = MORNING + (state.endsWith("due") ? 628 : state.equals("two-minute") ? 719 : 805);
          String body =
              String.format(
                  java.util.Locale.US,
                  "{\"serverTime\":%d,\"feedTime\":%d,\"status\":\"ok\",\"noticesReady\":true,"
                      + "\"weather\":{\"kind\":\"rain\"},\"arrivals\":[{\"id\":\"early\",\"time\":%d},{\"id\":\"%s\",\"time\":%d,\"destination\":\"South"
                      + " Ferry\"}]}",
                  MORNING,
                  feed,
                  MORNING + 240,
                  id,
                  target);
          String envelope =
              String.format(
                  java.util.Locale.US,
                  "{\"serverTime\":%d,\"feedTime\":%d,\"noticesReady\":true,",
                  MORNING,
                  MORNING);
          if (state.startsWith("anonymous-null")) body = body.replace("\"id\":\"\"", "\"id\":null");
          if (state.startsWith("anonymous-number")) body = body.replace("\"id\":\"\"", "\"id\":5");
          if (state.equals("malformed")) body = envelope + "\"status\":\"ok\"}";
          if (state.equals("empty")) body = envelope + "\"status\":\"ok\",\"arrivals\":[]}";
          if (state.equals("disrupted"))
            body =
                envelope
                    + "\"status\":\"disrupted\",\"arrivals\":[],\"alert\":\"Downtown boarding is"
                    + " suspended for this test.\"}";
          write(exchange, body.getBytes(StandardCharsets.UTF_8));
          served.set(state);
        });
    server.start();
    ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
    MainActivity app = controller.get();
    try {
      app.setTheme(android.R.style.Theme_Material_NoActionBar);
      controller.setup();
      android.media.AudioManager audio = (android.media.AudioManager) app.getSystemService(MainActivity.AUDIO_SERVICE);
      audio.setRingerMode(android.media.AudioManager.RINGER_MODE_NORMAL);
      audio.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, 5, 0);
      View root = app.findViewById(android.R.id.content);
      until(() -> boardSays(root, "Leave in 3 min"));
      assertEquals("116/1/S", LocalBoard.lastTrip);
      HostUiTest.named(root, "Settings").performClick();
      HostUiTest.named(root, "Connection").performClick();
      assertTrue(HostUiTest.named(root, "This phone").isSelected());
      HostUiTest.named(root, "Home server").performClick();
      String address = "127.0.0.1:" + server.getAddress().getPort();
      HostUiTest.first(root, EditText.class).setText(address);
      HostUiTest.named(root, "Test connection").performClick();
      SharedPreferences prefs = app.getSharedPreferences("subway-board", 0);
      until(
          () ->
              String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                  .startsWith("Connected"));
      HostUiTest.named(root, "Sound").performClick();
      HostUiTest.named(root, "Departure chime     Off").performClick();
      HostUiTest.named(root, "Save").performClick();
      until(() -> boardSays(root, "Leave in 3 min"));
      assertEquals(
          "http://" + address,
          new JSONObject(prefs.getString("settings", "{}")).getString("server"));
      assertEquals("station=116&route=1&direction=S", selection.get());
      assertTrue(boardSays(root, "14 min"));
      assertTrue(boardSays(root, "Forecast: Rain possible"));
      assertFalse(prefs.contains("lastWindow"));
      poll(mode, "malformed", root, "Invalid board data");
      poll(mode, "empty", root, "No upcoming trains");
      poll(mode, "anonymous", root, "14 min");
      assertTrue(boardSays(root, "South Ferry"));
      poll(mode, "anonymous-due", root, "Leave now");
      assertFalse(prefs.contains("lastWindow"));
      for (String type : new String[]{"anonymous-null", "anonymous-number"}) {
        poll(mode, type, root, "Leave in 3 min");
        poll(mode, type + "-due", root, "Leave now");
        assertFalse(prefs.contains("lastWindow"));
      }
      poll(mode, "initial", root, "Leave in 3 min");
      assertEquals(0, ShadowToneGenerator.getPlayedTones().size());
      poll(mode, "two-minute", root, "Leave in 1 min");
      until(() -> ShadowToneGenerator.getPlayedTones().size() == 1);
      assertTrue(prefs.getStringSet("soundTargets", Collections.emptySet()).contains("120/116/1/S/first"));
      poll(mode, "due", root, "Leave now");
      until(() -> ShadowToneGenerator.getPlayedTones().size() == 2);
      assertTrue(prefs.getStringSet("soundTargets", Collections.emptySet()).contains("30/116/1/S/first"));
      poll(mode, "initial", root, "Leave in 3 min");
      poll(mode, "due", root, "Leave now");
      assertEquals(2, ShadowToneGenerator.getPlayedTones().size());
      assertTrue(prefs.contains("lastWindow"));
      controller.pause().resume();
      until(() -> boardSays(root, "Leave now"));
      poll(mode, "initial", root, "Leave in 3 min");
      poll(mode, "due", root, "Leave now");
      assertEquals(2, ShadowToneGenerator.getPlayedTones().size());
      poll(mode, "second", root, "Leave in 3 min");
      poll(mode, "second-due", root, "Leave now");
      assertEquals(2, prefs.getStringSet("soundTargets", Collections.emptySet()).size());
      assertEquals(2, ShadowToneGenerator.getPlayedTones().size());
      poll(mode, "stale", root, "Arrivals delayed");
      assertFalse(boardSays(root, "Leave now"));
      poll(mode, "disrupted", root, "Service change");
      saveBoard(HostUiTest.first(root, BoardView.class), "board-service-change");
      HostUiTest.named(root, "Settings").performClick();
      HostUiTest.named(root, "Sound").performClick();
      HostUiTest.named(root, "Test chime").performClick();
      assertEquals(3, ShadowToneGenerator.getPlayedTones().size());
      audio.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, 0, 0);
      HostUiTest.named(root, "Test chime").performClick();
      assertEquals(3, ShadowToneGenerator.getPlayedTones().size());
      audio.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, 5, 0);
      HostUiTest.named(root, "Connection").performClick();
      HostUiTest.named(root, "This phone").performClick();
      HostUiTest.named(root, "Save").performClick();
      until(() -> boardSays(root, "South Ferry"));
      assertEquals("", new JSONObject(prefs.getString("settings", "{}")).getString("server"));
      assertFalse(boardSays(root, "Service change"));
    } finally {
      controller.pause().stop().destroy();
      server.stop(0);
    }
  }

  private void poll(AtomicReference<String> mode, String state, View root, String expected)
      throws Exception {
    served.set(null);
    mode.set(state);
    // Advance to the next real polling boundary, then allow the network worker to complete.
    for (int waitedSeconds = 0;
        waitedSeconds < 80 && !state.equals(served.get());
        waitedSeconds++) {
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
      Thread.sleep(30);
    }
    assertEquals("local service received requested fixture", state, served.get());
    until(() -> boardSays(root, expected));
  }

  private static boolean boardSays(View root, String text) {
    for (View v : HostUiTest.tree(root)) {
      if (v.getContentDescription() != null && v.getContentDescription().toString().contains(text))
        return true;
    }
    return false;
  }

  private static void saveBoard(BoardView board, String image) throws Exception {
    HostUiTest.measure(board);
    until(board::isMapCompositionReady);
    HostUiTest.save(board, image);
  }

  private static void until(BooleanSupplier result) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    do {
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      if (result.getAsBoolean()) return;
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);
    fail("Timed out waiting for the activity to apply the local service response");
  }

  private static void write(HttpExchange exchange, byte[] response) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    try (var body = exchange.getResponseBody()) {
      body.write(response);
    }
  }
}
