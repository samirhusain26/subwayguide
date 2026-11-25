package nyc.subway.board;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, qualifiers = "w640dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HostUiTest {
  private Activity activity;
  private static final int W = 1280, H = 720;

  @Before
  public void start() {
    var c = Robolectric.buildActivity(Activity.class);
    activity = c.get();
    activity.setTheme(android.R.style.Theme_Material_NoActionBar);
    c.setup();
  }

  static List<View> tree(View root) {
    List<View> all = new ArrayList<>();
    all.add(root);
    if (root instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++) all.addAll(tree(group.getChildAt(i)));
    }
    return all;
  }

  static View named(View v, String s) {
    for (View item : tree(v))
      if (s.contentEquals(item.getContentDescription() == null ? "" : item.getContentDescription())
          || (item instanceof TextView && s.contentEquals(((TextView) item).getText())))
        return item;
    throw new AssertionError("Missing control " + s);
  }

  static <T> T first(View root, Class<T> type) {
    for (View v : tree(root)) if (type.isInstance(v)) return type.cast(v);
    throw new AssertionError(type);
  }

  void show(View v) {
    measure(v);
  }

  static void measure(View v) {
    v.measure(
        View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
    v.layout(0, 0, W, H);
  }

  static void save(View view, String file) throws Exception {
    measure(view);
    Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(b));
    assertNotEquals("empty screen", 0, b.getPixel(300, 300));
    try (OutputStream s =
        Files.newOutputStream(Paths.get(System.getProperty("outDir"), file + ".png"))) {
      assertTrue(b.compress(Bitmap.CompressFormat.PNG, 100, s));
    }
    b.recycle();
  }

  private static InputStream asset(String name) throws IOException {
    return Files.newInputStream(Paths.get(System.getProperty("assetDir"), name));
  }

  @Test
  public void board() throws Exception {
    final int[] clicks = {0};
    BoardView b = new BoardView(activity, () -> clicks[0]++, HostUiTest::asset);
    show(b);
    long now = 1795500000L;
    List<Decision.Train> t =
        Arrays.asList(
            new Decision.Train("a", now + 240, "South Ferry"),
            new Decision.Train("b", now + 780, "South Ferry"),
            new Decision.Train("c", now + 1260, "South Ferry"));
    Decision d = Decision.evaluate(now, now, "ok", t, 10, 0);
    assertEquals("Leave in 3 min", d.headline);
    b.update("116", "125 St", "1", "S", "Downtown", d.headline, d.detail, d.next, "rain", "Rain possible", "", true, d, now);
    assertTrue(b.getChildAt(0).getContentDescription().toString().contains("Train in 13 min · South Ferry"));
    String map = b.getChildAt(1).getContentDescription().toString();
    assertTrue(map, map.contains("Previous: 137 St-City College"));
    assertTrue(map, map.contains("Next: 116 St-Columbia University"));
    save(b, "board-rain");
    assertTrue(b.getChildAt(1).performClick());
    assertTrue(b.getChildAt(1).getContentDescription().toString().startsWith("Show nearby stops"));
    save(b, "board-full-route");
    assertTrue(b.getChildAt(1).performClick());
    d = Decision.evaluate(now + 180, now + 180, "ok", t, 10, 0);
    assertEquals("Leave now", d.headline);
    b.update("116", "125 St", "1", "S", "Downtown", d.headline, d.detail, d.next, "sun", "Sunny", "", true, d, now + 180);
    save(b, "board-leave-now");
    d = Decision.evaluate(now, now - 61, "ok", t, 10, 0);
    assertNull(d.targetId);
    b.update(
        "116", "125 St", "1", "S", "Downtown", d.headline, "Last train update 8:09 AM", d.next, "", "", "", true, null, now);
    save(b, "board-delayed");

    List<Decision.Train> branches = Arrays.asList(
        new Decision.Train("d", now + 300, "Kings Hwy"),
        new Decision.Train("e", now + 780, "Coney Island-Stillwell Av"),
        new Decision.Train("f", now + 1020, "Kings Hwy"));
    d = Decision.evaluate(now, now, "ok", branches, 10, 0);
    b.update("A41", "Jay St-MetroTech", "FX", "S", "Outbound", d.headline, d.detail, d.next, "night", "Clear night", "", true, d, now);
    String spoken = b.getChildAt(0).getContentDescription().toString();
    assertTrue(spoken.contains("F express Outbound"));
    assertTrue(spoken.contains("Train in 13 min · Coney Island-Stillwell Av"));
    save(b, "board-express");

    assertTrue(spoken.contains("Following train in 17 min, Kings Hwy"));
    d = Decision.evaluate(now, now, "ok", Arrays.asList(
        new Decision.Train("a", now + 600, "Kings Hwy"),
        new Decision.Train("b", now + 600, "Coney Island-Stillwell Av")), 10, 0);
    b.update("A41", "Jay St-MetroTech", "FX", "S", "Outbound", d.headline, d.detail, d.next,
        "", "", "", true, d, now);
    assertTrue(b.getChildAt(0).getContentDescription().toString()
        .contains("Following train in 10 min, Coney Island-Stillwell Av"));
    save(b, "board-equal-eta");

    String[][] layouts = {
      {"A27", "42 St-Port Authority Bus Terminal", "A", "Downtown & Brooklyn"},
      {"902", "Times Sq-42 St", "GS", "Grand Central"},
      {"S01", "Franklin Av", "FS", "Prospect Park"},
      {"H04", "Broad Channel", "H", "Rockaway Park"},
      {"S09", "Tottenville", "SI", "St George"}
    };
    d = Decision.evaluate(now, now, "ok", Collections.singletonList(
        new Decision.Train("long-eta", now + 6600, "Far Rockaway-Mott Av")), 10, 1);
    for (String[] selection : layouts) {
      b.update(selection[0], selection[1], selection[2], selection[0].equals("S09") || selection[0].equals("902") ? "N" : "S", selection[3], d.headline,
          d.detail, d.next, "", "", "", true, d, now);
      assertFalse(b.getChildAt(0).getContentDescription().toString().contains("Or leave"));
      save(b, "symbolic-" + selection[2]);
    }
    b.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
    b.layout(0, 0, 640, 480);
    Bitmap narrow = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
    b.draw(new Canvas(narrow));
    assertNotEquals(0, narrow.getPixel(300, 240));
    narrow.recycle();
    show(b);

    Bitmap badge = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888);
    Canvas badgeCanvas = new Canvas(badge);
    BoardView.drawRouteBullet(badgeCanvas, "FX", 30, 30, 25);
    assertEquals("express diamond excludes corner", 0, badge.getPixel(14, 14));
    assertEquals("F uses the orange trunk color", 0xffeb6800, badge.getPixel(30, 12));
    BoardView.drawRouteBullet(badgeCanvas, "F", 30, 30, 25);
    assertEquals("local circle includes corner", 0xffeb6800, badge.getPixel(14, 14));
    badge.recycle();
    View settings = named(b, "Settings");
    assertTrue(settings.getWidth() >= 96);
    assertTrue(settings.performClick());
    assertEquals(1, clicks[0]);
  }

  @Test
  public void mapContinuesUnderTheCountdown() {
    BoardView board = new BoardView(activity, () -> {}, HostUiTest::asset);
    measure(board);
    board.update("116", "125 St", "1", "S", "Downtown", "Finding trains", "", "", "", "", "",
        true, null, 0);
    Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    board.draw(new Canvas(bitmap));
    // This strip is between the heading and the countdown. Streets should still be
    // visible here, close to the paper color, with no boundary at the map control.
    int lightest = 0, darkest = 255;
    for (int x = 16; x < 450; x += 3) {
      for (int y = 154; y < 176; y += 3) {
        int red = android.graphics.Color.red(bitmap.getPixel(x, y));
        lightest = Math.max(lightest, red);
        darkest = Math.min(darkest, red);
      }
    }
    assertTrue("street detail under the text", lightest - darkest >= 3);
    assertTrue("paper wash under the text", darkest > 211);
    int boundary = (int)(RouteMap.LEFT * 2);
    int waterLeft = android.graphics.Color.red(bitmap.getPixel(boundary-1, 165));
    int waterRight = android.graphics.Color.red(bitmap.getPixel(boundary+1, 165));
    assertTrue("continuous shoreline wash", Math.abs(waterLeft - waterRight) < 8);
    bitmap.recycle();
  }

  @Test
  public void choosesExpressAndBranchStopsFromArrival() {
    BoardView board = new BoardView(activity, () -> {}, HostUiTest::asset);
    long now = 12345678;
    for (String[] trip : new String[][] {
        {"712", "7", "7X", "707N", "Flushing-Main St", "Junction Blvd"},
        {"S22", "SI", "SI", "S31N", "St George", "St George"},
        {"S22", "SI", "SI", "S23N", "St George", "Grant City"}}) {
      Decision.Train train = new Decision.Train("target", now+800, trip[4], trip[2], trip[3]);
      Decision d = Decision.evaluate(now, now, "ok", Collections.singletonList(train), 10, 0);
      board.update(trip[0], "Selected station", trip[1], "N", "Northbound", d.headline,
          d.detail, d.next, "", "", "", true, d, now);
      assertTrue(board.getChildAt(1).getContentDescription().toString().contains("Next: " + trip[5] + "."));
    }
  }

  @Test
  public void catalogBoardSelections() throws Exception {
    BoardView board = new BoardView(activity, () -> {}, HostUiTest::asset);
    show(board);
    JSONArray stations = catalog();
    long now = 1795500000L;
    for (int i = 0; i < stations.length(); i++) {
      JSONObject station = stations.getJSONObject(i);
      JSONObject labels = station.getJSONObject("directions");
      JSONObject routes = station.getJSONObject("routeDirections");
      String id = station.getString("id"), name = station.getString("name");
      for (Iterator<String> keys = routes.keys(); keys.hasNext();) {
        String route = keys.next();
        JSONArray directions = routes.getJSONArray(route);
        for (int j = 0; j < directions.length(); j++) {
          String label = labels.getString(directions.getString(j));
          for (int count : new int[] {0, 1, 2, 5}) {
            List<Decision.Train> predictions = new ArrayList<>();
            for (int k = 0; k < count; k++)
              predictions.add(new Decision.Train("train-" + k, now + 780 + 240L * k, "Terminal " + k));
            Decision d = Decision.evaluate(now, now, "ok", predictions, 10, 1);
            board.update(id, name, route, directions.getString(j), label, d.headline, d.detail, d.next, "", "", "", true, d, now);
            String spoken = board.getChildAt(0).getContentDescription().toString();
            assertTrue(id + "/" + route + "/" + label, spoken.contains("Scheduled stops."));
            assertTrue(spoken.startsWith(name + ", " + BoardView.displayRoute(route)
                + (BoardView.isExpress(route) ? " express" : "") + " " + label));
            assertEquals(count > 0, spoken.contains("Train in 13 min · Terminal 0"));
            assertEquals(count > 1, spoken.contains("Following train in 17 min, Terminal 1"));
          }
        }
      }
    }
  }

  JSONObject prefs() throws Exception {
    return new JSONObject()
        .put("station", "116")
        .put("route", "1")
        .put("direction", "S")
        .put("server", "")
        .put("walk", 10)
        .put("buffer", 0)
        .put("chime", false)
        .put("chimeRepeat", false)
        .put("days", 31)
        .put("start", 420)
        .put("end", 540)
        .put("dim", true)
        .put("reduceMotion", false);
  }

  JSONArray catalog() throws Exception {
    return new JSONObject(Files.readString(Paths.get(System.getProperty("catalogFile"))))
        .getJSONArray("stations");
  }

  final JSONObject[] saved = {null};
  final String[] tested = {null};
  boolean canceled;
  SettingsScreen.Listener listener =
      new SettingsScreen.Listener() {
        public void testChime() {}

        public void save(JSONObject c) {
          saved[0] = c;
        }

        public void cancel() {
          canceled = true;
        }

        public void loadStations(String s) {
          tested[0] = s;
        }

        public void changeSource(String s) {}
      };

  static void reach(View root, String label) {
    measure(root);
    ScrollView scroller = (ScrollView) ((ViewGroup) root).getChildAt(1);
    View item = named(root, label);
    Rect visible = new Rect();
    int greatestVisibleHeight = 0;
    // Sweep the scrollable range to prove the entire target can be brought into view.
    // Each offset is clamped by the production ScrollView.
    for (int y = 1; y <= scroller.getChildAt(0).getHeight(); y += 6) {
      scroller.scrollTo(0, y);
      if (item.getGlobalVisibleRect(visible)) {
        greatestVisibleHeight = Math.max(greatestVisibleHeight, visible.height());
        if (greatestVisibleHeight == item.getHeight()) break;
      }
    }
    assertTrue("pane scrolls", scroller.getScrollY() > 0);
    assertEquals(
        label + " can be fully scrolled into view", item.getHeight(), greatestVisibleHeight);
    View save = named(root, "Save");
    assertTrue("Save stays visible", save.getGlobalVisibleRect(visible));
    assertEquals(save.getHeight(), visible.height());
  }

  @Test
  public void settings() throws Exception {
    SettingsScreen s = new SettingsScreen(activity, catalog(), prefs(), listener);
    show(s);
    save(s, "settings-trip");
    assertTrue(named(s, "Update app").performClick());
    Intent update = Shadows.shadowOf(activity).getNextStartedActivity();
    assertEquals(Intent.ACTION_VIEW, update.getAction());
    assertEquals("https://github.com/samirhusain26/subwayguide/releases/latest/download/SubwayBoard.apk",
        update.getDataString());
    assertTrue(named(s, "Connection").performClick());
    save(s, "settings-connection");
    assertTrue(named(s, "This phone").isSelected());
    assertTrue(named(s, "Home server").performClick());
    EditText host = first(s, EditText.class);
    host.setText("subwayboard.local:8765");
    assertTrue(named(s, "Test connection").performClick());
    assertEquals("subwayboard.local:8765", tested[0]);
    assertTrue(named(s, "Trip").performClick());
    View picker =
        tree(s).stream()
            .filter(
                v ->
                    v instanceof TextView
                        && ((TextView) v).getText().toString().startsWith("125 St"))
            .findFirst()
            .get();
    picker.performClick();
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    assertNotNull(dialog);
    EditText query = first(dialog.getWindow().getDecorView(), EditText.class);
    query.setText("Columbus Circle");
    ListView list = first(dialog.getWindow().getDecorView(), ListView.class);
    int row = -1;
    for (int i = 0; i < list.getCount(); i++)
      if (list.getItemAtPosition(i).toString().contains("A B C D")) {
        row = i;
        break;
      }
    assertTrue("multi-route station found", row >= 0);
    list.performItemClick(
        list.getAdapter().getView(row, null, list), row, list.getAdapter().getItemId(row));
    named(s, "C").performClick();
    named(s, "Uptown").performClick();
    reach(s, "Increase Walk to the platform");
    named(s, "Increase Walk to the platform").performClick();
    reach(s, "Increase Extra boarding time");
    named(s, "Increase Extra boarding time").performClick();
    named(s, "Sound").performClick();
    named(s, "Departure chime     Off").performClick();
    save(s, "settings-sound");
    reach(s, "Until   9:00 am");
    named(s, "Until   9:00 am").performClick();
    android.app.Dialog timeDialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
    assertTrue(timeDialog instanceof android.app.TimePickerDialog);
    ((android.app.TimePickerDialog) timeDialog).updateTime(9, 30);
    ((android.app.TimePickerDialog) timeDialog)
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .performClick();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    named(s, "Sat").performClick();
    named(s, "Chime for each catchable train     Off").performClick();
    named(s, "Display").performClick();
    named(s, "Reduce motion     Off").performClick();
    named(s, "Save").performClick();
    assertEquals("A24", saved[0].getString("station"));
    assertEquals("C", saved[0].getString("route"));
    assertEquals("N", saved[0].getString("direction"));
    assertEquals(11, saved[0].getInt("walk"));
    assertEquals(63, saved[0].getInt("days"));
    assertEquals(570, saved[0].getInt("end"));
    assertEquals(1, saved[0].getInt("buffer"));
    assertTrue(saved[0].getBoolean("chime"));
    assertTrue(saved[0].getBoolean("chimeRepeat"));
    assertTrue(saved[0].getBoolean("reduceMotion"));
    assertEquals("subwayboard.local:8765", saved[0].getString("server"));
    SettingsScreen reopened = new SettingsScreen(activity, catalog(), saved[0], listener);
    show(reopened);
    assertTrue(named(reopened, "Uptown, selected").isSelected());
    assertTrue(named(reopened, "C, selected").isSelected());
    named(reopened, "‹   Cancel").performClick();
    assertTrue(canceled);
  }

  @Test
  public void changedDirectoryRequiresAValidRoute() throws Exception {
    JSONObject trip = prefs();
    SettingsScreen s = new SettingsScreen(activity, catalog(), trip, listener);
    JSONArray replacement = new JSONArray().put(new JSONObject()
        .put("id", "116").put("name", "Station").put("routes", new JSONArray().put("2"))
        .put("routeDirections", new JSONObject().put("2", new JSONArray().put("S")))
        .put("directions", new JSONObject().put("S", "Downtown")));
    s.setStations(replacement);
    named(s, "Save").performClick();
    assertNull(saved[0]);
    named(s, "2").performClick();
    named(s, "Save").performClick();
    assertEquals("2", saved[0].getString("route"));
  }

  @Test
  public void largeText() throws Exception {
    Configuration config = new Configuration(activity.getResources().getConfiguration());
    config.fontScale = 1.3f;
    Context c = activity.createConfigurationContext(config);
    c.setTheme(android.R.style.Theme_Material_NoActionBar);
    SettingsScreen s = new SettingsScreen(c, catalog(), prefs(), listener);
    named(s, "Connection").performClick();
    show(s);
    save(s, "settings-large-type");
    assertEquals(1, ((TextView) named(s, "Connection")).getLineCount());
    assertTrue(named(s, "Save").getHeight() >= 96);
  }
}
