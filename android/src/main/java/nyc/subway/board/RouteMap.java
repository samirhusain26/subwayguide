package nyc.subway.board;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline north-up geographic panel. All coordinates use the atlas's local metre grid. */
final class RouteMap {
  interface Assets { InputStream open(String name) throws IOException; }
  static final float LEFT = 298, RIGHT = 640, BOTTOM = 248;
  private static final int INK = 0xff203a37, PAPER = 0xfffaf8ee;
  // Both washes use the board paper color. Their low-opacity ends keep the station
  // corridor crisp, while the near-opaque ends leave a trace of streets behind the type.
  private static final int WASH = BoardView.PAPER & 0x00ffffff;
  private final Shader textWash = new LinearGradient(35, 100, 482, 72,
      new int[] {0xdb000000 | WASH, 0xdf000000 | WASH, 0xca000000 | WASH,
          0x82000000 | WASH, 0x24000000 | WASH, WASH},
      new float[] {0, .32f, .5f, .68f, .85f, 1}, Shader.TileMode.CLAMP);
  private final Shader arrivalWash = new LinearGradient(0, 188, 0, 338,
      new int[] {WASH, 0x24000000 | WASH, 0x98000000 | WASH, 0xdb000000 | WASH,
          0xeb000000 | WASH}, new float[] {0, .23f, .51f, .77f, 1}, Shader.TileMode.CLAMP);
  private final Assets assets;
  private final JSONObject network;
  private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final List<RectF> labels = new ArrayList<>();
  private JSONArray stops, shape, station;
  private int index = -1, color;
  private float cx, cy, metres;
  private boolean wide;

  RouteMap(Assets assets) {
    this.assets = assets;
    JSONObject loaded;
    try (InputStream in = assets.open("nyc/network.json")) {
      loaded = new JSONObject(ServiceClient.read(in));
    } catch (Exception unavailable) {
      loaded = new JSONObject();
    }
    network = loaded;
  }

  void select(String id, String route, String side, String destination, String onward) {
    index = -1;
    stops = shape = null;
    JSONObject stations = network.optJSONObject("stations"), routes = network.optJSONObject("routes");
    station = stations == null ? null : stations.optJSONArray(id);
    JSONArray options = routes == null ? null : routes.optJSONArray(route);
    color = BoardView.routeColor(route);
    if (options == null || station == null) return;
    String platform = id + side;
    // Break ties by scheduled frequency. Terminal stop names match the realtime service's
    // destination wording even when the scheduled trip headsign is abbreviated.
    int best = -1;
    for (int i = 0; i < options.length(); i++) {
      JSONArray pattern = options.optJSONArray(i);
      JSONArray path = pattern.optJSONArray(2);
      String terminal = path.optString(path.length() - 1);
      JSONArray end = stations.optJSONArray(terminal.substring(0, terminal.length() - 1));
      boolean sameEnd = pattern.optString(1).equalsIgnoreCase(destination)
          || end.optString(0).equalsIgnoreCase(destination);
      for (int j = 0; j < path.length() - 1; j++) {
        if (!platform.equals(path.optString(j))) continue;
        int score = (sameEnd ? 1 : 0) + (path.optString(j + 1).equals(onward) ? 2 : 0);
        if (score <= best) continue;
        index = j;
        stops = path;
        shape = network.optJSONObject("shapes").optJSONArray(pattern.optString(0));
        best = score;
        if (best == 3) return;
      }
    }
  }

  private JSONArray stop(int i) {
    String platform = stops.optString(i);
    return network.optJSONObject("stations").optJSONArray(platform.substring(0, platform.length() - 1));
  }

  String description() {
    if (index < 0) return "Scheduled route map unavailable.";
    return "Scheduled stops. " + (index > 0 ? "Previous: " + stop(index - 1).optString(0) + ". " : "First stop. ")
        + "Selected: " + station.optString(0) + ". Next: " + stop(index + 1).optString(0) + ".";
  }

  boolean available() { return index >= 0; }

  void draw(Canvas c, boolean fullRoute) {
    wide = fullRoute && available();
    c.save();
    Rect bounds = c.getClipBounds();
    c.drawColor(0xffeeede4);
    float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
    float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    if (available()) {
      int from = wide ? 0 : Math.max(0, index - 1);
      int to = wide ? stops.length() - 1 : Math.min(stops.length() - 1, index + 1);
      for (int i = from; i <= to; i++) {
        JSONArray s = stop(i);
        float x = s.optInt(1), y = s.optInt(2);
        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
      }
      if (wide && shape != null) {
        for (int i = 0; i < shape.length(); i += 2) {
          int x = shape.optInt(i), y = shape.optInt(i+1);
          minX = Math.min(minX, x); maxX = Math.max(maxX, x);
          minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
      }
      metres = Math.max(12f, Math.max((maxX - minX) / 184, (maxY - minY) / 128));
      cx = (minX + maxX) / 2;
      cy = (minY + maxY) / 2;
    } else {
      cx = station == null ? 29200 : station.optInt(1);
      cy = station == null ? 33000 : station.optInt(2);
      metres = station == null ? 225 : 22;
    }
    pen.setAntiAlias(false);
    drawTiles(c, bounds);
    pen.setAntiAlias(true);
    labels.clear();
    labels.add(new RectF(LEFT, 0, RIGHT, 48)); // Orientation label and settings.
    labels.add(new RectF(RIGHT-111, BOTTOM-31, RIGHT, BOTTOM));
    if (available()) drawTrack(c);
    pen.setColor(0xffffffff);
    pen.setDither(true);
    pen.setShader(textWash);
    c.drawRect(bounds, pen);
    pen.setShader(arrivalWash);
    c.drawRect(bounds, pen);
    pen.setShader(null);
    if (available()) {
      stopLabel(c, index, true);
      if (!wide) {
        if (index > 0) stopLabel(c, index - 1, false);
        stopLabel(c, index + 1, false);
      }
    }
    drawPlaces(c);
    pen.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    pen.setTextSize(12);
    String area = station == null ? "" : station.optString(3).toUpperCase(Locale.US);
    pen.setColor(INK);
    c.drawText("NYC" + (area.isEmpty() || wide ? "  /  SUBWAY" : "  /  " + area), LEFT + 10, 22, pen);
    pen.setTextSize(10);
    c.drawText(available() ? "Scheduled stops" : "Route map unavailable", LEFT + 10, 38, pen);
    pen.setTextSize(12);
    if (available()) {
      String action = wide ? "Nearby stops" : "Full route";
      chip(c, RIGHT - 20 - pen.measureText(action), BOTTOM - 13, action);
    }
    c.restore();
  }

  private float x(float v) { return (LEFT + RIGHT) / 2 + (v - cx) / metres; }
  private float y(float v) { return BOTTOM / 2 + (v - cy) / metres; }

  private void drawTiles(Canvas c, Rect bounds) {
    boolean close = metres < 43;
    if (!close || bounds.left < x(0) || bounds.right > x(65536)
        || bounds.top < y(0) || bounds.bottom > y(65536)) {
      drawTilesAt(c, bounds, 64);
    }
    if (close) drawTilesAt(c, bounds, 8);
  }

  private void drawTilesAt(Canvas c, Rect bounds, int scale) {
    int span = 512 * scale;
    int minCol = scale == 64 ? -4 : 0, maxCol = scale == 64 ? 4 : 16;
    int minRow = scale == 64 ? -3 : 0, maxRow = scale == 64 ? 5 : 16;
    int firstCol = Math.max(minCol, (int)Math.floor((cx + (bounds.left - (LEFT + RIGHT)/2) * metres) / span));
    int firstRow = Math.max(minRow, (int)Math.floor((cy + (bounds.top - BOTTOM/2) * metres) / span));
    pen.setColor(0xffffffff);
    for (int col = firstCol; col < maxCol && x(col * span) < bounds.right; col++) {
      for (int row = firstRow; row < maxRow && y(row * span) < bounds.bottom; row++) {
        try (InputStream in = assets.open("nyc/" + scale + "-" + col + "-" + row + ".png")) {
          Bitmap tile = BitmapFactory.decodeStream(in);
          if (tile == null) continue;
          c.drawBitmap(tile, null, new RectF(x(col*span), y(row*span),
              x((col+1)*span), y((row+1)*span)), pen);
          tile.recycle(); // This is a software canvas backed by the board's cached bitmap.
        } catch (IOException ignored) { /* Keep route and stop labels available. */ }
      }
    }
  }

  private void drawTrack(Canvas c) {
    Path line = new Path();
    if (shape != null) {
      for (int i = 0; i < shape.length(); i += 2) {
        float px = x(shape.optInt(i)), py = y(shape.optInt(i + 1));
        if (i == 0) line.moveTo(px, py); else line.lineTo(px, py);
      }
    }
    pen.setStrokeCap(Paint.Cap.ROUND);
    pen.setStrokeJoin(Paint.Join.ROUND);
    pen.setStyle(Paint.Style.STROKE);
    pen.setStrokeWidth(wide ? 5 : 7);
    pen.setColor(PAPER); c.drawPath(line, pen);
    pen.setStrokeWidth(wide ? 2.7f : 4); pen.setColor(color); c.drawPath(line, pen);
    pen.setStyle(Paint.Style.FILL);
    for (int i = 0; i < stops.length(); i++) {
      JSONArray s = stop(i);
      float px = x(s.optInt(1)), py = y(s.optInt(2));

      float r = wide ? 1.6f : 4.1f;
      pen.setColor(INK); c.drawCircle(px, py, r, pen);
      pen.setColor(PAPER); c.drawCircle(px, py, r * .63f, pen);
      if (Math.abs(index-i) <= 1) labels.add(new RectF(px-r-2,py-r-2,px+r+2,py+r+2));
    }
    float px = x(station.optInt(1)), py = y(station.optInt(2));
    pen.setColor(PAPER); c.drawCircle(px, py, 8, pen);
    pen.setColor(INK); c.drawCircle(px, py, 6, pen);
    pen.setColor(color); c.drawCircle(px, py, 3.7f, pen);
  }

  private void stopLabel(Canvas c, int i, boolean selected) {
    JSONArray s = stop(i);
    float px = x(s.optInt(1)), py = y(s.optInt(2));
    String name = s.optString(0);
    pen.setTypeface(Typeface.create("sans-serif", selected ? Typeface.BOLD : Typeface.NORMAL));
    pen.setTextSize(selected ? 15 : 13);
    if (pen.measureText(name) > 169 && !selected) pen.setTextSize(12);
    int chars = pen.breakText(name, true, 200, null);
    if (chars < name.length()) name = name.substring(0, chars) + "…";
    float w = pen.measureText(name);
    for (float dy : new float[] {4, -12, 22, -28, 39}) {
      for (boolean right : new boolean[] {true, false}) {
        float left = Math.max(LEFT+4, Math.min(RIGHT-w-8, right ? px+11 : px-11-w));
        float base = py + dy;
        RectF box = new RectF(left-3, base-pen.getTextSize(), left+w+3, base+4);
        if (base < 48 || box.bottom > BOTTOM || overlaps(box)) continue;
        labels.add(box);
        if (Math.abs(dy) > 5) {
          pen.setColor(0xff59665f); pen.setStrokeWidth(.8f);
          c.drawLine(px, py, right ? box.left : box.right, box.centerY(), pen);
        }
        pen.setColor(selected ? INK : 0xeefaf8ee); c.drawRoundRect(box, 3, 3, pen);
        pen.setColor(selected ? PAPER : INK); c.drawText(name, left, base, pen);
        return;
      }
    }
  }

  private boolean overlaps(RectF box) {
    for (RectF reserved : labels) if (RectF.intersects(reserved, box)) return true;
    return false;
  }

  private static final Object[][] PLACES = {
    {"Hudson River", -73.967, 40.818}, {"Central Park", -73.966, 40.782},
    {"East River", -73.957, 40.749}, {"Prospect Park", -73.969, 40.661},
    {"Upper Bay", -74.048, 40.663}, {"Jamaica Bay", -73.846, 40.608},
    {"BRONX", -73.86, 40.861}, {"MANHATTAN", -73.979, 40.761},
    {"QUEENS", -73.82, 40.73}, {"BROOKLYN", -73.95, 40.637},
    {"STATEN ISLAND", -74.147, 40.59}, {"NEW JERSEY", -74.048, 40.788}
  };

  private void drawPlaces(Canvas c) {
    pen.setTextSize(11);
    pen.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
    for (Object[] place : PLACES) {
      float px = x((float)(((double)place[1] + 74.35) * 84300));
      float py = y((float)((41.01 - (double)place[2]) * 111100));
      String title = (String)place[0];
      float half = pen.measureText(title) / 2;
      RectF box = new RectF(px-half-2, py-12, px+half+2, py+3);
      if (box.left < LEFT+4 || box.right > RIGHT-4 || py < 46 || py > BOTTOM-4 || overlaps(box)) continue;
      labels.add(box);
      pen.setColor(0xff496568);
      c.drawText(title, px-half, py, pen);
    }
  }

  private void chip(Canvas c, float left, float base, String text) {
    pen.setColor(0xf3faf8ee);
    c.drawRoundRect(left-5, base-14, left + pen.measureText(text)+6, base+5, 5, 5, pen);
    pen.setColor(INK);
    c.drawText(text, left, base, pen);
  }
}
