package nyc.subway.board;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, qualifiers = "w640dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BoardMapRegressionTest {
  private Activity activity;
  private static final int WIDTH = 1280, HEIGHT = 720;

  @Before public void start() {
    var controller = Robolectric.buildActivity(Activity.class);
    activity = controller.get();
    activity.setTheme(android.R.style.Theme_Material_NoActionBar);
    controller.setup();
  }

  private static InputStream asset(String name) throws IOException {
    return Files.newInputStream(Paths.get(System.getProperty("assetDir"), name));
  }

  private static void layout(View view) {
    view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, WIDTH, HEIGHT);
  }

  private static View tagged(View root, Object tag) {
    if (tag.equals(root.getTag())) return root;
    if (root instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup)root;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = tagged(group.getChildAt(i), tag);
        if (found != null) return found;
      }
    }
    return null;
  }

  @Test public void explanationAndServiceNoticeHaveSeparateActions() throws Exception {
    BoardView board = new BoardView(activity, () -> {}, BoardMapRegressionTest::asset);
    layout(board);
    long now = 1795500000L;
    Decision d = Decision.evaluate(now, now, "ok", Arrays.asList(
        new Decision.Train("a", now + 780, "South Ferry"),
        new Decision.Train("b", now + 780, "South Ferry")), 10, 1);
    board.updateContext("W times unavailable", "", 10, 1, Collections.emptyList());
    board.update("116", "125 St", "1", "S", "Downtown", d.headline, d.detail, d.next,
        "", "", "Downtown trains skip 145 St", true, d, now);
    assertTrue(board.getChildAt(0).getContentDescription().toString().contains("W times unavailable"));
    assertTrue(board.getChildAt(0).performClick());
    Dialog explanation = ShadowAlertDialog.getLatestDialog();
    assertTrue(message(explanation).contains("Walk: 10 min"));
    assertTrue(message(explanation).contains("Boarding allowance: 1 min"));
    View notice = tagged(board, "service-notice-action");
    assertNotNull("notice has its own reachable action", notice);
    assertTrue(notice.performClick());
    Dialog noticeDialog = ShadowAlertDialog.getLatestDialog();
    assertTrue(message(noticeDialog).contains("Downtown trains skip 145 St"));
  }

  private static String message(Dialog dialog) {
    android.widget.TextView text = dialog.getWindow().getDecorView().findViewById(android.R.id.message);
    return text == null ? "" : text.getText().toString();
  }

  @Test public void freshArrivalsRemainVisibleWhileNoticeClearanceIsPending() {
    BoardView board = new BoardView(activity, () -> {}, BoardMapRegressionTest::asset);
    layout(board);
    board.updateContext("Checking W service notices", "N · 4 min\nW · 11 min", 10, 0,
        Collections.emptyList());
    board.update("635", "14 St-Union Sq", "N", "N", "Uptown", "Checking service",
        "Fresh train times", "", "", "", "", true, null, 1795500000L);
    Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    board.draw(new Canvas(bitmap));
    // The two ETA lines occupy the advice area, well above the map/arrival wash.
    assertTrue("visible arrival text is rendered", hasInk(bitmap, 50, 440, 570, 550));
    assertTrue(board.getChildAt(0).getContentDescription().toString().contains("N · 4 min"));
    bitmap.recycle();
  }

  @Test public void cueGlyphMeetsSevenFootDesignReferenceInHostRender() {
    long now = 1795500000L;
    for (String label : new String[] {"3", "Now"}) {
      BoardView board = new BoardView(activity, () -> {}, BoardMapRegressionTest::asset);
      layout(board);
      long trainTime = now + ("Now".equals(label) ? 600 : 780);
      Decision decision = Decision.evaluate(now, now, "ok", Collections.singletonList(
          new Decision.Train("target", trainTime, "South Ferry")), 10, 0);
      board.update("116", "125 St", "1", "S", "Downtown", decision.headline, decision.detail,
          decision.next, "", "", "", true, decision, now);
      Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
      board.draw(new Canvas(bitmap));
      int[] bounds = inkBounds(bitmap, 40, 230, 560, 530);
      assertNotNull(label + " cue is drawn", bounds);
      int glyphHeight = bounds[3] - bounds[1] + 1;
      System.out.println("BoardMapRegressionTest " + label + " cue glyph host px=" + glyphHeight);
      assertTrue(label + " seven-foot design reference is about 220 visible pixels", glyphHeight >= 220);
      bitmap.recycle();
    }
  }

  private static int[] inkBounds(Bitmap bitmap, int left, int top, int right, int bottom) {
    int minX = right, minY = bottom, maxX = -1, maxY = -1;
    for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
      int color = bitmap.getPixel(x, y);
      if (android.graphics.Color.red(color) < 90 && android.graphics.Color.green(color) < 100
          && android.graphics.Color.blue(color) < 90) {
        minX = Math.min(minX, x); minY = Math.min(minY, y);
        maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
      }
    }
    return maxX < 0 ? null : new int[] {minX, minY, maxX, maxY};
  }

  @Test public void measuresMapChangeAndCachedBoardDrawOnHost() throws Exception {
    BoardView board = new BoardView(activity, () -> {}, BoardMapRegressionTest::asset);
    layout(board);
    long now = 1795500000L;
    Decision decision = Decision.evaluate(now, now, "ok", Collections.singletonList(
        new Decision.Train("target", now + 900, "Brighton Beach", "Q", "R30S")), 10, 0);
    board.update("Q01", "Canal St", "Q", "S", "Brooklyn-bound", decision.headline,
        decision.detail, decision.next, "", "", "", true, decision, now);
    Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    long coldStart = System.nanoTime();
    board.draw(new Canvas(bitmap));
    long coldMs = (System.nanoTime() - coldStart) / 1_000_000;
    View mapToggle = board.getChildAt(1);
    assertTrue(mapToggle.performClick());
    long warmStart = System.nanoTime();
    board.draw(new Canvas(bitmap));
    long warmMapMs = (System.nanoTime() - warmStart) / 1_000_000;
    long cachedStart = System.nanoTime();
    board.draw(new Canvas(bitmap));
    long cachedMs = (System.nanoTime() - cachedStart) / 1_000_000;
    System.out.println("BoardMapRegressionTest Q Canal host cold-map-ms=" + coldMs
        + " warm-scale-change-ms=" + warmMapMs + " cached-board-ms=" + cachedMs
        + " (host-only; handset unmeasured)");
    bitmap.recycle();
  }

  @Test public void productionBoardKeepsTextLiveAndOnlyPublishesLatestMap() throws Exception {
    BoardView board = new BoardView(activity, () -> {});
    activity.setContentView(board);
    layout(board);
    long now = 1795500000L;
    Decision first = Decision.evaluate(now, now, "ok", Collections.singletonList(
        new Decision.Train("q", now + 900, "Brighton Beach", "Q", "R30S")), 10, 0);
    board.update("Q01", "Canal St", "Q", "S", "Brooklyn-bound", first.headline,
        first.detail, first.next, "", "", "", true, first, now);
    Decision latest = Decision.evaluate(now, now, "ok", Collections.singletonList(
        new Decision.Train("one", now + 780, "South Ferry")), 10, 0);
    long immediateStart = System.nanoTime();
    board.update("116", "125 St", "1", "S", "Downtown", latest.headline,
        latest.detail, latest.next, "", "", "", true, latest, now);
    Bitmap immediate = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    board.draw(new Canvas(immediate));
    long immediateMs = (System.nanoTime() - immediateStart) / 1_000_000;
    immediate.recycle();
    System.out.println("BoardMapRegressionTest production update plus text draw ms=" + immediateMs);
    assertTrue("new trip text appears before map composition finishes",
        board.getChildAt(0).getContentDescription().toString().startsWith("125 St, 1 Downtown"));
    long deadline = System.currentTimeMillis() + 8000;
    while (!board.isMapCompositionReady() && System.currentTimeMillis() < deadline)
      Thread.sleep(10);
    assertTrue("latest map composition completes", board.isMapCompositionReady());
    assertTrue("map description tracks the selected station",
        board.getChildAt(1).getContentDescription().toString().contains("Next: 116 St-Columbia University"));
    HostUiTest.save(board, "board-async-map-current-selection");
  }

  @Test public void overlappingProductionBoardsBothReceiveQueuedCompositions() throws Exception {
    FrameLayout root = new FrameLayout(activity);
    activity.setContentView(root);
    BoardView first = new BoardView(activity, () -> {});
    BoardView second = new BoardView(activity, () -> {});
    root.addView(first, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
    root.addView(second, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
    layout(first); layout(second);
    long now = 1795500000L;
    Decision a = Decision.evaluate(now, now, "ok", Collections.singletonList(
        new Decision.Train("a", now + 900, "South Ferry")), 10, 0);
    Decision b = Decision.evaluate(now, now, "ok", Collections.singletonList(
        new Decision.Train("b", now + 900, "Brighton Beach")), 10, 0);
    first.update("116", "125 St", "1", "S", "Downtown", a.headline, a.detail,
        a.next, "", "", "", true, a, now);
    second.update("Q01", "Canal St", "Q", "S", "Brooklyn-bound", b.headline,
        b.detail, b.next, "", "", "", true, b, now);
    long deadline = System.currentTimeMillis() + 10000;
    while ((!first.isMapCompositionReady() || !second.isMapCompositionReady())
        && System.currentTimeMillis() < deadline) Thread.sleep(10);
    assertTrue("first attached map completes", first.isMapCompositionReady());
    assertTrue("second attached map completes without queue rejection", second.isMapCompositionReady());
  }

  private static boolean hasInk(Bitmap bitmap, int left, int top, int right, int bottom) {
    for (int y = top; y < bottom; y += 2) for (int x = left; x < right; x += 2) {
      int color = bitmap.getPixel(x, y);
      if (android.graphics.Color.red(color) < 100 && android.graphics.Color.green(color) < 110
          && android.graphics.Color.blue(color) < 100) return true;
    }
    return false;
  }

  @Test public void clippedShortTurnGeometryEndsAtLastPassengerStop() throws Exception {
    RouteMap map = new RouteMap(BoardMapRegressionTest::asset);
    // Local 6 short turn at 3 Av-138 St: the source GTFS shape continues toward Brook Av.
    map.select("621", "6", "N", "3 Av-138 St", "619N", Collections.emptyList());
    assertTrue(map.available());
    float[] six = map.geometryEndpoints();
    assertEndpoint(six, 35732, 22167);
    // 4 short turn at Bedford Park Blvd-Lehman College: the shape continues to Mosholu Pkwy.
    map.select("406", "4", "N", "Bedford Park Blvd-Lehman College", "405N", Collections.emptyList());
    assertTrue(map.available());
    float[] four = map.geometryEndpoints();
    assertEndpoint(four, 38773, 15175);
  }

  private static void assertEndpoint(float[] endpoints, float x, float y) {
    assertEquals(4, endpoints.length);
    assertTrue("endpoint equals the last scheduled passenger stop",
        (Math.hypot(endpoints[0]-x, endpoints[1]-y) < 4)
            || (Math.hypot(endpoints[2]-x, endpoints[3]-y) < 4));
  }

  @Test public void downstreamPlatformsSelectOrDiscloseRepresentativePatterns() throws Exception {
    RouteMap map = new RouteMap(BoardMapRegressionTest::asset);
    map.select("S21", "SI", "N", "St George", "S22N", Collections.emptyList());
    assertTrue(map.available());
    assertTrue("duplicate scheduled possibilities are identified", map.representative());
    map.select("S21", "SI", "N", "St George", "S22N", Arrays.asList(
        "S22N", "S23N", "S24N", "S25N"));
    assertTrue(map.available());
    assertFalse("reported downstream sequence identifies the local pattern", map.representative());
  }

  @Test public void actualShortTurnTerminalOverridesMatchingNormalRoutePrefix() throws Exception {
    RouteMap map = new RouteMap(BoardMapRegressionTest::asset);
    // Parent station IDs are reported by realtime while atlas terminal entries are platform IDs.
    map.select("116", "1", "S", "South Ferry", "117S", Collections.emptyList(), "142");
    assertTrue(map.available());
    assertFalse("parent 142 matches scheduled platform endpoint 142S", map.representative());
    // A conflicting later downstream stop prevents the matching terminal alone from proving
    // this exact stop pattern.
    map.select("116", "1", "S", "South Ferry", "117S",
        Arrays.asList("117S", "120S", "138S"), "142");
    assertTrue("contradictory downstream evidence stays representative", map.representative());
    // The normal scheduled 1 continues from Chambers to South Ferry. A train whose actual
    // terminal is parent 137 shares the immediate onward stop but must not claim that full pattern.
    map.select("116", "1", "S", "Chambers St", "117S", Collections.emptyList(), "137");
    assertTrue(map.available());
    assertTrue("short-turn terminal mismatch is disclosed", map.representative());
    assertTrue(map.description().startsWith("Representative scheduled route."));
  }
}
