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
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A single sign face, laid out in 640 x 360 design units, with a native settings target. */
public final class BoardView extends FrameLayout {
  private static final ThreadPoolExecutor MAP_COMPOSER = new ThreadPoolExecutor(1, 1,
      0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
        Thread thread = new Thread(runnable, "Board-map-composer");
        thread.setDaemon(true);
        return thread;
      });
  static final int PAPER = 0xfff7f6ed;
  private static final int INK = 0xff17231e, MUTED = 0xff43564b;
  private static final Typeface NORMAL = Typeface.create("sans-serif", Typeface.NORMAL);
  private static final Typeface HEAVY = Typeface.create("sans-serif", Typeface.BOLD);
  private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
  private static final Paint BULLET_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
  private String selection = "", station = "", route = "", direction = "";
  private String headline = "", detail = "", weatherKind = "", weather = "", alert = "";
  private String routeStatus = "", platformArrivals = "", consideredLines = "";
  private int walkMinutes, bufferMinutes;
  private List<String> onwardStops = java.util.Collections.emptyList();
  private Decision guidance;
  private long now;
  private volatile Bitmap background;
  private volatile int backgroundRevision = -1;
  private volatile int mapRevision;
  private final Object bitmapLock = new Object();
  private FutureTask<Void> mapTask;
  private final boolean asyncMapComposition;
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
  private final Runnable openSettings;

  public BoardView(Context context, Runnable openSettings) {
    this(context, openSettings, name -> LocalBoard.openAsset(context, name), true);
  }

  BoardView(Context context, Runnable openSettings, RouteMap.Assets assets) {
    this(context, openSettings, assets, false);
  }

  private BoardView(Context context, Runnable openSettings, RouteMap.Assets assets,
      boolean asyncMapComposition) {
    super(context);
    this.openSettings = openSettings;
    this.asyncMapComposition = asyncMapComposition;
    map = new RouteMap(assets);
    setWillNotDraw(false);
    setBackgroundColor(PAPER);
    spokenBoard = new View(context);
    spokenBoard.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    addView(spokenBoard, new LayoutParams(-1, -1));
    spokenBoard.setOnClickListener(v -> showExplanation(context));
    spokenBoard.setAccessibilityDelegate(new View.AccessibilityDelegate() {
      @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
            AccessibilityNodeInfo.ACTION_CLICK, "Explain departure"));
      }
    });
    spokenBoard.setFocusable(true);
    spokenBoard.setContentDescription("Departure board");
    mapTarget = new View(context);
    mapTarget.setFocusable(true);
    mapTarget.setOnClickListener(v -> {
      fullRoute = !fullRoute;
      mapDirty = true;
      mapRevision++;
      discardBackground();
      describeMapControl();
      if (asyncMapComposition) queueMapComposition();
      invalidate();
    });
    addView(mapTarget, new LayoutParams(1, 1));
    View noticeTarget = new View(context);
    noticeTarget.setFocusable(true);
    noticeTarget.setContentDescription("Service notice. Tap to read.");
    noticeTarget.setOnClickListener(v -> showNotice(context));
    addView(noticeTarget, new LayoutParams(1, 1));
    noticeTarget.setTag("service-notice-action");
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

  /** Supplies route-specific coverage and the evidence used to explain the chosen train. */
  void updateContext(String routeStatus, String platformArrivals, int walkMinutes,
      int bufferMinutes, List<String> onwardStops) {
    updateContext(routeStatus, platformArrivals, walkMinutes, bufferMinutes, onwardStops, "");
  }

  void updateContext(String routeStatus, String platformArrivals, int walkMinutes,
      int bufferMinutes, List<String> onwardStops, String consideredLines) {
    this.routeStatus = routeStatus == null ? "" : routeStatus.trim();
    this.platformArrivals = platformArrivals == null ? "" : platformArrivals.trim();
    this.walkMinutes = Math.max(0, walkMinutes);
    this.bufferMinutes = Math.max(0, bufferMinutes);
    this.onwardStops = onwardStops == null ? java.util.Collections.emptyList() : new java.util.ArrayList<>(onwardStops);
    this.consideredLines = consideredLines == null ? "" : consideredLines.trim();
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
    String terminalId = hasTarget ? guidance.primary.terminalId : "";
    String pattern = actualRoute + "/" + onward + "/" + destination + "/" + onwardStops.toString()
        + "/" + terminalId;
    if (newSelection || !mapPattern.equals(pattern)) {
      if (newSelection) fullRoute = false;
      map.select(stationId, actualRoute, side, destination, onward, onwardStops, terminalId);
      mapPattern = pattern;
      mapDirty = true;
      mapRevision++;
      discardBackground();
      describeMapControl();
      if (asyncMapComposition) queueMapComposition();
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
        + (next.isEmpty() && platformArrivals.isEmpty() ? "" : ". Platform arrivals: "
            + (platformArrivals.isEmpty() ? next : platformArrivals).replace("\n", "; "))
        + (routeStatus.isEmpty() ? "" : ". " + routeStatus)
        + (weather.isEmpty() ? "" : ". Forecast: " + weather)
        + (alert.isEmpty() ? "" : ". Service notice: " + alert);
    if (hasTarget) description += ". Walk " + walkMinutes + " minutes"
        + (bufferMinutes > 0 ? ", with " + bufferMinutes + " minute boarding allowance" : "") + ".";
    description += ". " + map.description();
    if (hasTarget && guidance.followingTime > 0) {
      description += (sameLeaveCue()
          ? ". Both trains share the same leave time" : ". " + alternateCue())
          + ". Following train in " + eta(guidance.followingTime)
          + (guidance.followingRoute.isEmpty() ? "" : " on " + routeName(guidance.followingRoute))
          + (guidance.followingDestination.isEmpty() ? "" : ", " + guidance.followingDestination);
    }
    if (!description.contentEquals(spokenBoard.getContentDescription() == null
        ? "" : spokenBoard.getContentDescription())) spokenBoard.setContentDescription(description);
    spokenBoard.setClickable(true);
    View noticeAction = findViewWithTag("service-notice-action");
    if (noticeAction != null) {
      noticeAction.setVisibility(alert.isEmpty() ? GONE : VISIBLE);
      noticeAction.setContentDescription("Service notice. " + alert + ". Tap to read.");
    }
    layoutNoticeAction();
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

  private void showNotice(Context context) {
    if (!alert.isEmpty()) new AlertDialog.Builder(context).setTitle("Service notice")
        .setMessage(alert).setPositiveButton("Done", null).show();
  }

  private void showExplanation(Context context) {
    String message;
    if (hasTarget(guidance)) {
      String targetRoute = guidance.targetRoute.isEmpty() ? route : guidance.targetRoute;
      message = "Target train · " + displayRoute(targetRoute)
          + (isExpress(targetRoute) ? " express" : "")
          + (guidance.targetDestination.isEmpty() ? "" : " to " + guidance.targetDestination)
          + ", in " + eta(guidance.targetTime)
          + "\nLines checked: " + (consideredLines.isEmpty()
              ? routeName(route) + (guidance.followingTime > 0 && !guidance.followingRoute.isEmpty()
                  && !guidance.followingRoute.equals(route) ? ", " + routeName(guidance.followingRoute) : "")
              : consideredLines)
          + "\nWalk: " + walkMinutes + " min"
          + (bufferMinutes > 0 ? "\nBoarding allowance: " + bufferMinutes + " min" : "")
          + (routeStatus.isEmpty() ? "" : "\n" + routeStatus);
    } else {
      message = detail + (platformArrivals.isEmpty() ? "" : "\nPlatform arrivals: " + platformArrivals)
          + (routeStatus.isEmpty() ? "" : "\n" + routeStatus);
    }
    if (map.representative()) message += "\nRepresentative scheduled route.";
    new AlertDialog.Builder(context).setTitle(headline.isEmpty() ? "Departure" : headline)
        .setMessage(message).setPositiveButton("Done", null).show();
  }

  private void stopMotion() {
    if (change != null) change.cancel();
    change = null;
    phase = 1;
  }

  @Override
  protected void onDetachedFromWindow() {
    stopMotion();
    cancelMapComposition();
    discardBackground();
    super.onDetachedFromWindow();
  }

  @Override protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (asyncMapComposition) queueMapComposition();
  }

  @Override
  protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    mapDirty = true;
    if (asyncMapComposition) {
      mapRevision++;
      discardBackground();
      queueMapComposition();
    }
  }

  private void describeMapControl() {
    mapTarget.setVisibility(map.available() ? VISIBLE : GONE);
    mapTarget.setContentDescription((fullRoute ? "Show nearby stops. " : "Show full scheduled route. ")
        + map.description());
  }

  private void layoutNoticeAction() {
    View noticeAction = findViewWithTag("service-notice-action");
    if (noticeAction == null) return;
    float scale = Math.min(getWidth() / 640f, getHeight() / 360f);
    int left = Math.round((getWidth() - 640 * scale) / 2);
    int top = Math.round((getHeight() - 360 * scale) / 2);
    noticeAction.layout(left + Math.round(20 * scale), top + Math.round(320 * scale),
        left + Math.round(620 * scale), top + Math.round(358 * scale));
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    float scale = Math.min(getWidth() / 640f, getHeight() / 360f);
    int x = Math.round((getWidth() - 640 * scale) / 2);
    int y = Math.round((getHeight() - 360 * scale) / 2);
    mapTarget.layout(x + Math.round(RouteMap.LEFT * scale), y, x + Math.round(640 * scale),
        y + Math.round(RouteMap.BOTTOM * scale));
    layoutNoticeAction();
  }

  private void composeMap() {
    discardBackground();
    background = renderMap(map, fullRoute, getWidth(), getHeight());
    backgroundRevision = mapRevision;
    mapBounds.set(0, 0, getWidth(), getHeight());
  }

  private Bitmap renderMap(RouteMap painter, boolean full, int viewWidth, int viewHeight) {
    if (viewWidth <= 0 || viewHeight <= 0) return null;
    float resolution = Math.min(1f, 1920f / Math.max(viewWidth, viewHeight));
    int width = Math.max(1, Math.round(viewWidth * resolution));
    int height = Math.max(1, Math.round(viewHeight * resolution));
    Bitmap rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    Canvas cached = new Canvas(rendered);
    float scale = Math.min(viewWidth / 640f, viewHeight / 360f);
    cached.scale(width / (float)viewWidth, height / (float)viewHeight);
    cached.translate((viewWidth - 640 * scale) / 2, (viewHeight - 360 * scale) / 2);
    cached.scale(scale, scale);
    painter.draw(cached, full);
    return rendered;
  }

  private void discardBackground() {
    synchronized (bitmapLock) {
      Bitmap old = background;
      background = null;
      backgroundRevision = -1;
      if (!asyncMapComposition && old != null && !old.isRecycled()) old.recycle();
    }
  }

  boolean isMapCompositionReady() {
    Bitmap current = background;
    return current != null && !current.isRecycled() && backgroundRevision == mapRevision;
  }

  private void cancelMapComposition() {
    synchronized (MAP_COMPOSER) {
      if (mapTask != null) {
        mapTask.cancel(true);
        MAP_COMPOSER.remove(mapTask);
        mapTask = null;
      }
    }
  }

  private void queueMapComposition() {
    if (!asyncMapComposition || getWidth() <= 0 || getHeight() <= 0 || mapPattern.isEmpty()) return;
    final int revision = mapRevision;
    final int width = getWidth(), height = getHeight();
    final boolean full = fullRoute;
    final RouteMap painter = map.snapshot();
    FutureTask<Void> task = new FutureTask<>(() -> {
      Bitmap rendered = null;
      try {
        rendered = renderMap(painter, full, width, height);
        if (Thread.currentThread().isInterrupted() || rendered == null) return null;
        synchronized (bitmapLock) {
          if (revision != mapRevision || !isAttachedToWindow()) return recycleResult(rendered);
          background = rendered;
          backgroundRevision = revision;
          rendered = null;
        }
        mapDirty = false;
        postInvalidate();
      } catch (RuntimeException ignored) {
        // A missing/corrupt offline tile leaves the live text board usable.
      } finally {
        if (rendered != null && !rendered.isRecycled()) rendered.recycle();
      }
      return null;
    });
    synchronized (MAP_COMPOSER) {
      if (mapTask != null) {
        mapTask.cancel(true);
        MAP_COMPOSER.remove(mapTask);
      }
      mapTask = task;
      MAP_COMPOSER.execute(mapTask);
    }
  }

  private static Void recycleResult(Bitmap bitmap) {
    if (!bitmap.isRecycled()) bitmap.recycle();
    return null;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float scale = Math.min(getWidth() / 640f, getHeight() / 360f);
    if (scale <= 0) return;
    if (!asyncMapComposition && mapDirty) {
      composeMap();
      mapDirty = false;
    }
    float offsetX = (getWidth() - 640 * scale) / 2;
    float offsetY = (getHeight() - 360 * scale) / 2;
    fill(PAPER);
    paint.setFilterBitmap(true);
    mapBounds.set(0, 0, getWidth(), getHeight());
    synchronized (bitmapLock) {
      Bitmap current = backgroundRevision == mapRevision ? background : null;
      if (current != null && !current.isRecycled()) canvas.drawBitmap(current, null, mapBounds, paint);
      else canvas.drawColor(PAPER);
    }
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
    if (asyncMapComposition && !isMapCompositionReady())
      fitted("Updating offline map…", RouteMap.LEFT + 10, 22, 12, 10, 180, MEDIUM, MUTED, canvas);
    if (!routeStatus.isEmpty()) fitted(routeStatus, 35, 76, 13, 11, 258, MEDIUM, MUTED, canvas);

    if (hasTarget(guidance)) {
      canvas.save();
      canvas.translate(0, (1 - phase) * 4);
      boolean leaveNow = guidance.leaveNow;
      text(leaveNow ? "Leave" : "Leave in", 34, 111, 24, MEDIUM, INK, Paint.Align.LEFT, canvas);
      String value = leaveNow ? "Now" : leaveValue(guidance.leaveSeconds);
      float size = fitSize(value, MEDIUM, 162, 340);
      text(value, 30, 259, size, MEDIUM, INK, Paint.Align.LEFT, canvas);
      if (!leaveNow)
        text("min", 38 + paint.measureText(value), 256, 28, MEDIUM, INK, Paint.Align.LEFT, canvas);
      canvas.restore();
      if (guidance.followingTime > 0 && !sameLeaveCue())
        fitted(alternateCue(), 35, 282, 18, 14, 270, MEDIUM, MUTED, canvas);
      drawOpportunities(canvas);
    } else {
      String visualHeadline = headline.toLowerCase(Locale.US).contains("delayed") ? "Delayed" : headline;
      float statusSize = visualHeadline.equals("Delayed") ? 78 : 42;
      fitted(visualHeadline, 34, 174, statusSize, 20, 254, HEAVY, INK, canvas);
      fitted(detail, 35, 205, 17, 14, 254, MEDIUM, MUTED, canvas);
      if (!platformArrivals.isEmpty()) {
        fitted("Platform arrivals", 35, 247, 14, 12, 245, MEDIUM, MUTED, canvas);
        String[] rows = platformArrivals.split("\\n");
        for (int i = 0; i < Math.min(2, rows.length); i++)
          fitted(rows[i], 35, 269 + i * 20, 16, 12, 245, MEDIUM, INK, canvas);
      }
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

  private boolean sameLeaveCue() {
    String primary = guidance.leaveNow ? "Now" : leaveValue(guidance.leaveSeconds);
    String following = guidance.alternateLeaveSeconds == 0 ? "Now" : leaveValue(guidance.alternateLeaveSeconds);
    return primary.equals(following);
  }

  private String eta(long time) {
    return ((time - now + 59) / 60) + " min";
  }

  private void drawOpportunities(Canvas canvas) {
    boolean shared = guidance.followingTime > 0
        && guidance.targetDestination.equalsIgnoreCase(guidance.followingDestination);
    arrival(canvas, 306, guidance.targetTime, shared ? "" : guidance.targetDestination, guidance.targetRoute, true);
    if (guidance.followingTime > 0)
      arrival(canvas, 477, guidance.followingTime, shared ? "" : guidance.followingDestination,
          guidance.followingRoute, false);
    if (shared) fitted(guidance.targetDestination, 308, 317, 13, 11, 314, NORMAL, MUTED, canvas);
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
