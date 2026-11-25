package nyc.subway.board;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import java.util.Locale;

/** A single sign face, laid out in 640 x 360 design units, with a native settings target. */
public final class BoardView extends FrameLayout {
  static final int PAPER = 0xfff7f6ed;
  private static final int INK = 0xff17231e, MUTED = 0xff43564b;
  private static final Typeface NORMAL = Typeface.create("sans-serif", Typeface.NORMAL);
  private static final Typeface HEAVY = Typeface.create("sans-serif", Typeface.BOLD);
  private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
  private static final Paint BULLET_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
  private String selection = "", station = "", route = "", direction = "";
  private String headline = "", detail = "", weatherKind = "", weather = "", alert = "";
  private Decision guidance;
  private long now;
  private Bitmap background;
  private final android.graphics.Rect mapBounds = new android.graphics.Rect();
  private boolean mapDirty = true;
  private final RouteMap map;
  private final View mapTarget;
  private boolean fullRoute;
  private String mapPattern = "";
  private ValueAnimator change;
  private float phase = 1;
  private final View spokenBoard;
  private final ImageButton settings;

  public BoardView(Context context, Runnable openSettings) {
    this(context, openSettings, context.getAssets()::open);
  }

  BoardView(Context context, Runnable openSettings, RouteMap.Assets assets) {
    super(context);
    map = new RouteMap(assets);
    setWillNotDraw(false);
    setBackgroundColor(PAPER);
    spokenBoard = new View(context);
    spokenBoard.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    addView(spokenBoard, new LayoutParams(-1, -1));
    spokenBoard.setOnClickListener(
        v -> {
          if (!alert.isEmpty())
            new AlertDialog.Builder(context)
                .setTitle("Service notice")
                .setMessage(alert)
                .setPositiveButton("Done", null)
                .show();
          else if (headline.equals("Connect your board") || headline.equals("Service offline"))
            openSettings.run();
        });
    mapTarget = new View(context);
    mapTarget.setFocusable(true);
    mapTarget.setOnClickListener(v -> {
      fullRoute = !fullRoute;
      mapDirty = true;
      describeMapControl();
      invalidate();
    });
    addView(mapTarget, new LayoutParams(1, 1));
    settings = new ImageButton(context);
    settings.setContentDescription("Settings");
    settings.setImageDrawable(new SettingsIcon());
    android.util.TypedValue ripple = new android.util.TypedValue();
    context
        .getTheme()
        .resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
    settings.setBackgroundResource(ripple.resourceId);
    settings.setScaleType(android.widget.ImageView.ScaleType.CENTER);
    settings.setOnClickListener(v -> openSettings.run());
    int size = dp(52);
    LayoutParams target = new LayoutParams(size, size, Gravity.TOP | Gravity.RIGHT);
    target.rightMargin = dp(9);
    target.topMargin = dp(6);
    addView(settings, target);
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  public void update(
      String stationId, String station, String route, String side, String direction,
      String headline, String detail, String next, String weatherKind, String weather, String alert,
      boolean reduceMotion, Decision guidance, long now) {
    String selected = stationId + "/" + route + "/" + side + "/" + direction;
    boolean newSelection = !selection.equals(selected);
    boolean hadTarget = hasTarget(this.guidance);
    boolean hasTarget = hasTarget(guidance);
    boolean targetChanged = hadTarget && hasTarget && !newSelection
        && (!java.util.Objects.equals(this.guidance.targetId, guidance.targetId)
            || !this.guidance.targetRoute.equals(guidance.targetRoute)
            || this.guidance.leaveNow != guidance.leaveNow);
    String destination = hasTarget ? guidance.targetDestination : "";
    String actualRoute = hasTarget && !guidance.primary.route.isEmpty() ? guidance.primary.route : route;
    String onward = hasTarget ? guidance.primary.onward : "";
    String pattern = actualRoute + "/" + onward + "/" + destination;
    if (newSelection || !mapPattern.equals(pattern)) {
      if (newSelection) fullRoute = false;
      map.select(stationId, actualRoute, side, destination, onward);
      mapPattern = pattern;
      mapDirty = true;
      describeMapControl();
    }
    this.selection = selected;
    this.station = station;
    this.route = actualRoute;
    this.direction = direction;
    this.headline = headline;
    this.detail = detail;
    this.weatherKind = weatherKind;
    this.weather = weather;
    this.alert = alert;
    this.guidance = guidance;
    this.now = now;
    String description = station + ", " + displayRoute(actualRoute)
        + (isExpress(actualRoute) ? " express" : "") + " " + direction + ". " + headline + ". " + detail
        + (next.isEmpty() ? "" : ". Platform arrivals: " + next.replace("\n", "; "))
        + (weather.isEmpty() ? "" : ". Forecast: " + weather)
        + (alert.isEmpty() ? "" : ". Service notice: " + alert);
    description += ". " + map.description();
    if (hasTarget && guidance.followingTime > 0) {
      description += ". " + alternateCue() + ". Following train in " + eta(guidance.followingTime)
          + (guidance.followingRoute.isEmpty() ? "" : " on " + routeName(guidance.followingRoute))
          + (guidance.followingDestination.isEmpty() ? "" : ", " + guidance.followingDestination);
    }
    if (!description.contentEquals(spokenBoard.getContentDescription() == null
        ? "" : spokenBoard.getContentDescription())) spokenBoard.setContentDescription(description);
    spokenBoard.setClickable(!alert.isEmpty() || headline.equals("Service offline"));
    if (reduceMotion || newSelection || !hasTarget || targetChanged) stopMotion();
    if (targetChanged && !reduceMotion && isShown()) {
      phase = 0;
      change = ValueAnimator.ofFloat(0, 1);
      change.setDuration(240);
      change.setInterpolator(new DecelerateInterpolator());
      change.addUpdateListener(animation -> {
        phase = (float) animation.getAnimatedValue();
        invalidate();
      });
      change.start();
    }
    invalidate();
  }

  private static boolean hasTarget(Decision decision) {
    return decision != null && decision.targetTime > 0;
  }

  private void stopMotion() {
    if (change != null) change.cancel();
    change = null;
    phase = 1;
  }

  @Override
  protected void onDetachedFromWindow() {
    stopMotion();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    mapDirty = true;
  }

  private void describeMapControl() {
    mapTarget.setVisibility(map.available() ? VISIBLE : GONE);
    mapTarget.setContentDescription((fullRoute ? "Show nearby stops. " : "Show full scheduled route. ")
        + map.description());
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    float scale = Math.min(getWidth() / 640f, getHeight() / 360f);
    int x = Math.round((getWidth() - 640 * scale) / 2);
    int y = Math.round((getHeight() - 360 * scale) / 2);
    mapTarget.layout(x + Math.round(RouteMap.LEFT * scale), y, x + Math.round(640 * scale),
        y + Math.round(RouteMap.BOTTOM * scale));
  }

  private void composeMap(float scale) {
    float resolution = Math.min(1f, 1920f / Math.max(getWidth(), getHeight()));
    int width = Math.max(1, Math.round(getWidth() * resolution));
    int height = Math.max(1, Math.round(getHeight() * resolution));
    if (background == null || background.getWidth() != width || background.getHeight() != height) {
      background = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }
    mapBounds.set(0, 0, getWidth(), getHeight());
    Canvas cached = new Canvas(background);
    cached.scale(width / (float)getWidth(), height / (float)getHeight());
    cached.translate((getWidth() - 640 * scale) / 2, (getHeight() - 360 * scale) / 2);
    cached.scale(scale, scale);
    map.draw(cached, fullRoute);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float scale = Math.min(getWidth() / 640f, getHeight() / 360f);
    if (scale <= 0) return;
    if (mapDirty) {
      composeMap(scale);
      mapDirty = false;
    }
    float offsetX = (getWidth() - 640 * scale) / 2;
    float offsetY = (getHeight() - 360 * scale) / 2;
    fill(PAPER);
    paint.setFilterBitmap(true);
    canvas.drawBitmap(background, null, mapBounds, paint);
    canvas.save();
    canvas.translate(offsetX, offsetY);
    canvas.scale(scale, scale);
    if (headline.isEmpty()) {
      canvas.restore();
      return;
    }
    float headingRight = RouteMap.LEFT - 6;
    if (!route.isEmpty()) drawRouteBullet(canvas, route, 43, 42, 16);
    fitted(station, 67, 44, 23, 14, headingRight - 67, HEAVY, INK, canvas);
    fitted(direction, 67, 64, 14, 12, headingRight - 67, MEDIUM, MUTED, canvas);

    if (hasTarget(guidance)) {
      canvas.save();
      canvas.translate(0, (1 - phase) * 4);
      boolean leaveNow = guidance.leaveNow;
      text(leaveNow ? "Leave" : "Leave in", 34, 132, 24, MEDIUM, INK, Paint.Align.LEFT, canvas);
      String value = leaveNow ? "Now" : leaveValue(guidance.leaveSeconds);
      float size = fitSize(value, MEDIUM, 132, leaveNow ? 255 : 220);
      text(value, 30, 239, size, MEDIUM, INK, Paint.Align.LEFT, canvas);
      if (!leaveNow)
        text("min", 38 + paint.measureText(value), 237, 28, MEDIUM, INK, Paint.Align.LEFT, canvas);
      canvas.restore();
      if (guidance.followingTime > 0)
        fitted(alternateCue(), 35, 274, 18, 14, 270, MEDIUM, MUTED, canvas);
      drawOpportunities(canvas);
    } else {
      fitted(headline, 34, 174, 42, 20, 254, HEAVY, INK, canvas);
      fitted(detail, 35, 205, 17, 14, 254, MEDIUM, MUTED, canvas);
    }

    if (!weather.isEmpty()) {
      drawWeatherIcon(canvas);
      fitted(weather, 62, 315, 16, 14, 250, MEDIUM, MUTED, canvas);
    }
    if (!alert.isEmpty()) {
      fill(0xffede5cb);
      canvas.drawRoundRect(24, 326, 616, 352, 7, 7, paint);
      fitted("Service notice  ·  " + alert, 35, 344, 15, 14, 550, MEDIUM, INK, canvas);
      text("›", 603, 344, 20, HEAVY, INK, Paint.Align.RIGHT, canvas);
    }
    canvas.restore();
  }

  private static String leaveValue(long seconds) {
    return seconds < 60 ? "<1" : Long.toString(seconds / 60);
  }

  private String alternateCue() {
    return guidance.alternateLeaveSeconds == 0 ? "Or leave now"
        : "Or leave in " + leaveValue(guidance.alternateLeaveSeconds) + " min";
  }

  private String eta(long time) {
    return ((time - now + 59) / 60) + " min";
  }

  private void drawOpportunities(Canvas canvas) {
    arrival(canvas, 306, guidance.targetTime, guidance.targetDestination, guidance.targetRoute, true);
    if (guidance.followingTime > 0)
      arrival(canvas, 477, guidance.followingTime, guidance.followingDestination, guidance.followingRoute, false);
  }

  private void arrival(Canvas canvas, float x, long time, String destination, String serviceRoute, boolean primary) {
    int color = routeColor(serviceRoute.isEmpty() ? route : serviceRoute);
    fill(color);
    canvas.drawCircle(x + 8, 270, 5, paint);
    if (!primary) {
      fill(PAPER);
      canvas.drawCircle(x + 8, 270, 3, paint);
    }
    fitted((primary ? "Target" : "Following") + " · " + routeName(serviceRoute.isEmpty() ? route : serviceRoute),
        x + 19, 274, 13, 11, 134, MEDIUM, MUTED, canvas);
    fitted(eta(time), x + 2, 299, 23, 15, 153, HEAVY, INK, canvas);
    fitted(destination, x + 2, 317, 13, 11, 153, NORMAL, MUTED, canvas);
  }

  static String routeName(String value) {
    if ("GS".equals(value)) return "42 St Shuttle";
    if ("FS".equals(value)) return "Franklin Shuttle";
    if ("H".equals(value)) return "Rockaway Shuttle";
    if ("SI".equals(value)) return "SIR";
    return value;
  }

  private void drawWeatherIcon(Canvas canvas) {
    canvas.save();
    canvas.translate(46, 309);
    fill(MUTED);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.5f);
    if ("sun".equals(weatherKind)) {
      canvas.drawCircle(0, 0, 4, paint);
      for (int i = 0; i < 8; i++) {
        canvas.drawLine(0, -7, 0, -9, paint);
        canvas.rotate(45);
      }
    } else if ("night".equals(weatherKind)) {
      canvas.drawArc(-8, -8, 8, 8, 35, 270, false, paint);
      canvas.drawArc(-1, -10, 12, 5, 80, 135, false, paint);
    } else if ("fog".equals(weatherKind)) {
      for (int i = -1; i <= 1; i++) canvas.drawLine(-8 + i, i * 4, 8 + i, i * 4, paint);
    } else if ("snow".equals(weatherKind)) {
      for (int i = 0; i < 6; i++) {
        canvas.drawLine(0, 0, 0, -8, paint);
        canvas.drawLine(0, -5, 3, -7, paint);
        canvas.drawLine(0, -5, -3, -7, paint);
        canvas.rotate(60);
      }
    } else {
      canvas.drawArc(-9, -4, 0, 4, 92, 195, false, paint);
      canvas.drawArc(-5, -8, 6, 3, 196, 165, false, paint);
      canvas.drawArc(1, -3, 10, 4, 270, 180, false, paint);
      if ("rain".equals(weatherKind) || "storm".equals(weatherKind)) {
        canvas.drawLine(-4, 3, -5, 7, paint);
        canvas.drawLine(1, 3, 0, 7, paint);
        canvas.drawLine(6, 3, 5, 7, paint);
      }
    }
    canvas.restore();
  }

  private float fitSize(String value, Typeface face, float wanted, float width) {
    paint.setTypeface(face);
    paint.setTextSize(wanted);
    float length = paint.measureText(value);
    return length > width ? wanted * width / length : wanted;
  }

  private void fill(int color) {
    paint.setColor(color);
    paint.setStyle(Paint.Style.FILL);
  }

  private void text(
      String value,
      float x,
      float baseline,
      float size,
      Typeface face,
      int color,
      Paint.Align align,
      Canvas c) {
    fill(color);
    paint.setTypeface(face);
    paint.setTextAlign(align);
    paint.setTextSize(size);
    c.drawText(value, x, baseline, paint);
  }

  private void fitted(
      String value,
      float x,
      float y,
      float size,
      float min,
      float width,
      Typeface face,
      int color,
      Canvas c) {
    if (width <= 0 || value.isEmpty()) return;
    paint.setTypeface(face);
    size *= Math.min(1.3f, getResources().getConfiguration().fontScale);
    paint.setTextSize(size);
    float length = paint.measureText(value);
    if (length > width) size = Math.max(min, size * width / length);
    paint.setTextSize(size);
    if (paint.measureText(value) > width) {
      int count = paint.breakText(value, true, Math.max(0, width - paint.measureText("…")), null);
      value = value.substring(0, count) + "…";
    }
    text(value, x, y, size, face, color, Paint.Align.LEFT, c);
  }

  static boolean isExpress(String route) {
    return "6X".equals(route) || "7X".equals(route) || "FX".equals(route);
  }

  static String displayRoute(String route) {
    if (route.equals("GS") || route.equals("FS") || route.equals("H")) return "S";
    if (isExpress(route)) return route.substring(0, 1);
    return route.equals("SI") ? "SIR" : route;
  }

  // Digital HEX values in the MTA Brand Colors / Subway, SIR & ADA sheet.
  static int routeColor(String route) {
    String mark = displayRoute(route.toUpperCase(Locale.US));
    if (mark.equals("SIR")) return 0xff08179c;
    if (mark.length() != 1) return 0xff7c858c;
    switch (mark.charAt(0)) {
      case '1': case '2': case '3': return 0xffd82233;
      case '4': case '5': case '6': return 0xff009952;
      case '7': return 0xff9a38a1;
      case 'A': case 'C': case 'E': return 0xff0062cf;
      case 'B': case 'D': case 'F': case 'M': return 0xffeb6800;
      case 'G': return 0xff799534;
      case 'J': case 'Z': return 0xff8e5c33;
      case 'N': case 'Q': case 'R': case 'W': return 0xfff6bc26;
      default: return 0xff7c858c;
    }
  }

  static void drawRouteBullet(Canvas canvas, String route, float x, float y, float radius) {
    String mark = displayRoute(route);
    Paint bullet = BULLET_PAINT;
    int color = routeColor(route);
    bullet.setColor(color);
    if (isExpress(route)) {
      float halfSide = radius * .70710678f;
      canvas.save();
      canvas.rotate(45, x, y);
      canvas.drawRect(x - halfSide, y - halfSide, x + halfSide, y + halfSide, bullet);
      canvas.restore();
    } else {
      canvas.drawCircle(x, y, radius, bullet);
    }
    bullet.setColor(color == 0xfff6bc26 ? 0xff111111 : 0xffffffff);
    bullet.setTypeface(HEAVY);
    bullet.setTextAlign(Paint.Align.CENTER);
    float size = radius * (mark.length() > 2 ? .86f : isExpress(route) ? 1.05f : 1.47f);
    bullet.setTextSize(size);
    canvas.drawText(mark, x, y + size * .355f, bullet);
  }

  private final class SettingsIcon extends Drawable {
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path gear = new Path();

    SettingsIcon() {
      // Tabler Settings outline, converted from the approved SVG path.
      gear.moveTo(10.325f, 4.317f);
      gear.rCubicTo(0.426f, -1.756f, 2.924f, -1.756f, 3.35f, 0f);
      gear.arcTo(13.62652f, 2.18703f, 17.07452f, 5.63503f, 166.37991f, -107.75114f, false);
      gear.rCubicTo(1.543f, -0.94f, 3.31f, 0.826f, 2.37f, 2.37f);
      gear.arcTo(18.36637f, 6.92582f, 21.81437f, 10.37382f, -148.65445f, -107.67751f, false);
      gear.rCubicTo(1.756f, 0.426f, 1.756f, 2.924f, 0f, 3.35f);
      gear.arcTo(18.36497f, 13.62652f, 21.81297f, 17.07452f, -103.62009f, -107.75114f, false);
      gear.rCubicTo(0.94f, 1.543f, -0.826f, 3.31f, -2.37f, 2.37f);
      gear.arcTo(13.62618f, 18.36637f, 17.07418f, 21.81437f, -58.65445f, -107.67751f, false);
      gear.rCubicTo(-0.426f, 1.756f, -2.924f, 1.756f, -3.35f, 0f);
      gear.arcTo(6.92548f, 18.36497f, 10.37348f, 21.81297f, -13.62009f, -107.75114f, false);
      gear.rCubicTo(-1.543f, 0.94f, -3.31f, -0.826f, -2.37f, -2.37f);
      gear.arcTo(2.18563f, 13.62618f, 5.63363f, 17.07418f, 31.34555f, -107.67751f, false);
      gear.rCubicTo(-1.756f, -0.426f, -1.756f, -2.924f, 0f, -3.35f);
      gear.arcTo(2.18703f, 6.92548f, 5.63503f, 10.37348f, 76.37991f, -107.75114f, false);
      gear.rCubicTo(-0.94f, -1.543f, 0.826f, -3.31f, 2.37f, -2.37f);
      gear.rCubicTo(1f, 0.608f, 2.296f, 0.07f, 2.572f, -1.065f);
      gear.close();
      gear.addCircle(12, 12, 3, Path.Direction.CW);
      pen.setColor(MUTED);
      pen.setStyle(Paint.Style.STROKE);
      pen.setStrokeWidth(2);
      pen.setStrokeCap(Paint.Cap.ROUND);
      pen.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas c) {
      c.save();
      c.translate(getBounds().left, getBounds().top);
      float scale = Math.min(getBounds().width(), getBounds().height()) / 24f;
      c.scale(scale, scale);
      c.drawPath(gear, pen);
      c.restore();
    }

    @Override
    public int getIntrinsicWidth() {
      return dp(24);
    }

    @Override
    public int getIntrinsicHeight() {
      return dp(24);
    }

    @Override
    public void setAlpha(int alpha) {
      pen.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
      pen.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
