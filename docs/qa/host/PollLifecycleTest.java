package nyc.subway.board;

import static org.junit.Assert.*;

import android.media.AudioManager;
import android.os.Looper;
import android.view.View;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToneGenerator;

/** Lifecycle regressions for sequential multi-route polls and chime timing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, qualifiers = "w640dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PollLifecycleTest {
  private static final long MORNING = Instant.parse("2026-11-24T13:00:00Z").getEpochSecond();

  @Test public void changingTripCancelsBlockedOldRouteBeforeItsSecondLine() throws Exception {
    CountDownLatch oldRouteStarted = new CountDownLatch(1);
    CountDownLatch releaseOldRoute = new CountDownLatch(1);
    Set<String> requests = ConcurrentHashMap.newKeySet();
    AtomicInteger mRequests = new AtomicInteger();
    HttpServer server = server(exchange -> {
      String route = query(exchange, "route");
      requests.add(route);
      if ("M".equals(route)) mRequests.incrementAndGet();
      if ("J".equals(route)) {
        oldRouteStarted.countDown();
        try {
          if (!releaseOldRoute.await(12, TimeUnit.SECONDS)) throw new IOException("fixture timeout");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException(interrupted);
        }
      }
      respond(exchange, board(route, MORNING, MORNING + 780));
    });
    server.start();
    ActivityController<MainActivity> controller = freshActivity(server, new JSONObject()
        .put("station", "M16").put("route", "J").put("routes", new JSONArray().put("J").put("M"))
        .put("direction", "S").put("destination", "Forest Hills-71 Av"));
    try {
      MainActivity activity = controller.get();
      View root = activity.findViewById(android.R.id.content);
      assertTrue("old multi-line poll reached its blocked first request",
          oldRouteStarted.await(5, TimeUnit.SECONDS));

      HostUiTest.named(root, "Settings").performClick();
      HostUiTest.named(root, "J, selected").performClick();
      HostUiTest.named(root, "Save").performClick();
      until(() -> boardSays(root, "Forest Hills-71 Av") && boardSays(root, "Leave in 3 min"));
      assertTrue("new trip polls the remaining route", requests.contains("M"));

      // Let the stale request finish only after the replacement trip has rendered.
      releaseOldRoute.countDown();
      Thread.sleep(150);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertEquals("the cancelled old poll must not advance to its obsolete second line", 1,
          mRequests.get());
      assertEquals(Set.of("J", "M"), requests);
      assertTrue(boardSays(root, "Forest Hills-71 Av"));
      assertFalse(boardSays(root, "Broad St"));
    } finally {
      releaseOldRoute.countDown();
      controller.pause().stop().destroy();
      server.stop(0);
    }
  }

  @Test public void delayedPollResponseDoesNotAdvanceTheThirtySecondReminderClock() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch secondPollStarted = new CountDownLatch(1);
    CountDownLatch releaseSecondPoll = new CountDownLatch(1);
    HttpServer server = server(exchange -> {
      int call = calls.incrementAndGet();
      if (call == 2) {
        secondPollStarted.countDown();
        try {
          if (!releaseSecondPoll.await(12, TimeUnit.SECONDS)) throw new IOException("fixture timeout");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException(interrupted);
        }
      }
      long serverTime = call == 1 ? MORNING : MORNING + 25;
      long trainTime = call == 1 ? MORNING + 660 : MORNING + 670;
      respond(exchange, board("1", serverTime, trainTime));
    });
    server.start();
    JSONObject settings = new JSONObject().put("station", "116").put("route", "1")
        .put("routes", new JSONArray().put("1")).put("direction", "S").put("walk", 10)
        .put("chime", true).put("days", 127).put("start", 0).put("end", 1439);
    ActivityController<MainActivity> controller = freshActivity(server, settings);
    try {
      MainActivity activity = controller.get();
      AudioManager audio = (AudioManager) activity.getSystemService(MainActivity.AUDIO_SERVICE);
      audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
      audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 5, 0);
      View root = activity.findViewById(android.R.id.content);
      until(() -> boardSays(root, "Leave in 1 min"));
      // Trigger the next 15-second board poll; its second response is held for ten seconds.
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15));
      assertTrue("the next poll began before the reminder threshold",
          secondPollStarted.await(5, TimeUnit.SECONDS));

      // The request remains open for ten seconds and returns a snapshot taken at +25s.
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
      releaseSecondPoll.countDown();
      until(() -> boardSays(root, "Brooklyn Bridge"));
      assertEquals("response receipt must not count its network latency twice", 0,
          ShadowToneGenerator.getPlayedTones().size());

      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(14));
      assertEquals("do not chime while the receipt-based countdown is still above 30 seconds", 0,
          ShadowToneGenerator.getPlayedTones().size());
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
      until(() -> ShadowToneGenerator.getPlayedTones().size() == 1);
      assertEquals(1, ShadowToneGenerator.getPlayedTones().size());
    } finally {
      releaseSecondPoll.countDown();
      controller.pause().stop().destroy();
      server.stop(0);
    }
  }

  private interface ExchangeHandler { void handle(HttpExchange exchange) throws IOException; }

  private static HttpServer server(ExchangeHandler handler) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, "poll-lifecycle-fixture");
      thread.setDaemon(true);
      return thread;
    }));
    server.createContext("/v1/board", handler::handle);
    return server;
  }

  private static ActivityController<MainActivity> freshActivity(HttpServer server, JSONObject trip)
      throws Exception {
    MainActivity.UPDATE_INSTALLING.set(false);
    ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
    MainActivity activity = controller.get();
    activity.getSharedPreferences("subway-board", 0).edit().clear().commit();
    trip.put("server", "http://127.0.0.1:" + server.getAddress().getPort());
    activity.getSharedPreferences("subway-board", 0).edit()
        .putString("settings", trip.toString()).commit();
    activity.setTheme(android.R.style.Theme_Material_NoActionBar);
    controller.setup();
    return controller;
  }

  private static JSONObject board(String route, long serverTime, long trainTime) {
    try {
      String destination = route.equals("J") ? "Broad St" : "Forest Hills-71 Av";
      if (serverTime > MORNING) destination = "Brooklyn Bridge";
      String terminalName = route.equals("J") ? "Broad St" : "Forest Hills-71 Av";
      String terminalId = route.equals("J") ? "M23" : "G08";
      JSONObject train = new JSONObject().put("id", "target-" + route).put("route", route)
          .put("time", trainTime).put("destination", destination)
          .put("terminalName", terminalName).put("terminalId", terminalId);
      return new JSONObject().put("status", "ok").put("serverTime", serverTime)
          .put("feedTime", serverTime).put("noticesReady", true).put("arrivals", new JSONArray().put(train));
    } catch (org.json.JSONException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String query(HttpExchange exchange, String key) {
    for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
      String[] parts = pair.split("=", 2);
      if (parts.length == 2 && key.equals(parts[0])) return parts[1];
    }
    return "";
  }

  private static void respond(HttpExchange exchange, JSONObject payload) throws IOException {
    byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (var body = exchange.getResponseBody()) { body.write(bytes); }
  }

  private static boolean boardSays(View root, String text) {
    for (View view : HostUiTest.tree(root)) {
      if (view.getContentDescription() != null
          && view.getContentDescription().toString().contains(text)) return true;
    }
    return false;
  }

  private static void until(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    do {
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      if (condition.getAsBoolean()) return;
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);
    fail("Timed out waiting for the lifecycle fixture");
  }
}
