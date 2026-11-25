package nyc.subway.board;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The four sections share a draft. Only Save publishes the new configuration. */
public final class SettingsScreen extends LinearLayout {
  public interface Listener {
    void save(JSONObject settings);

    void cancel();

    void testChime();

    void loadStations(String server);

    void changeSource(String server);
  }

  private static final int PAPER = 0xfff6f6f4, INK = 0xff101010, MUTED = 0xff515459;
  private static final int SOFT = 0xffe5e6e4, WHITE = 0xffffffff;
  private static final String[] TABS = {"Trip", "Display", "Sound", "Connection"};
  private static final Uri UPDATE_APK = Uri.parse(
      "https://github.com/samirhusain26/subwayguide/releases/latest/download/SubwayBoard.apk");
  private final Listener listener;
  private final JSONObject draft;
  private JSONArray catalog;
  private final LinearLayout nav, body;
  private final ScrollView scroll;
  private int section;
  private EditText serverInput;
  private boolean usePhone;

  public SettingsScreen(
      Context context, JSONArray stations, JSONObject current, Listener listener) {
    super(context);
    this.listener = listener;
    this.catalog = stations;
    try {
      draft = new JSONObject(current.toString());
    } catch (JSONException e) {
      throw new IllegalArgumentException("Invalid preferences", e);
    }
    setOrientation(HORIZONTAL);
    setBackgroundColor(PAPER);
    LinearLayout rail = column();
    rail.setBackgroundColor(INK);
    rail.setPadding(dp(10), dp(10), dp(10), dp(8));
    float fontScale = getResources().getConfiguration().fontScale;
    addView(rail, new LayoutParams(dp(Math.min(208, 140 * Math.max(1f, fontScale))), -1));
    View stripe = new View(context);
    stripe.setBackgroundColor(WHITE);
    rail.addView(stripe, new LayoutParams(-1, dp(3)));
    ScrollView navScroll = new ScrollView(context);
    rail.addView(navScroll, new LayoutParams(-1, 0, 1));
    nav = column();
    navScroll.addView(nav);
    TextView save = control("Save", WHITE, INK, this::save);
    rail.addView(save, new LayoutParams(-1, dp(48)));
    scroll = new ScrollView(context);
    scroll.setFillViewport(true);
    addView(scroll, new LayoutParams(0, -1, 1));
    body = column();
    body.setFocusableInTouchMode(true);
    body.setPadding(dp(18), dp(12), dp(18), dp(20));
    scroll.addView(body);
    usePhone = current.optString("server").isEmpty();
    draw();
  }

  public void setStations(JSONArray stations) {
    captureAddress();
    catalog = stations;
    draw();
  }

  boolean showsDirectory(JSONArray list) {
    return catalog == list || catalog.toString().equals(list.toString());
  }

  void chooseTripFrom(JSONArray list) {
    catalog = list;
    select(0);
  }

  boolean hasSource(String origin) {
    if (origin.isEmpty()) return usePhone;
    return !usePhone && origin.equals(ServiceClient.origin(serviceAddress()));
  }

  private void put(String key, Object value) {
    try {
      draft.put(key, value);
    } catch (JSONException e) {
      throw new IllegalArgumentException(key, e);
    }
  }

  String serviceAddress() {
    if (usePhone) return "";
    return serverInput == null ? draft.optString("server") : serverInput.getText().toString().trim();
  }

  private void captureAddress() {
    if (serverInput != null) put("server", serverInput.getText().toString().trim());
  }

  private void select(int tab) {
    captureAddress();
    section = tab;
    draw();
  }

  private void draw() {
    serverInput = null;
    body.removeAllViews();
    nav.removeAllViews();
    nav.addView(
        control("‹   Cancel", INK, 0xffc3c5c7, listener::cancel), new LayoutParams(-1, dp(48)));
    for (int i = 0; i < TABS.length; i++) {
      final int dest = i;
      TextView item =
          control(
              TABS[i],
              i == section ? 0xff343536 : INK,
              WHITE,
              () -> select(dest));
      item.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
      nav.addView(item, new LayoutParams(-1, dp(48)));
    }
    nav.addView(control("Update app", INK, WHITE, this::openUpdate), new LayoutParams(-1, dp(48)));
    TextView title = label(TABS[section], 29, INK, true);
    View rule = new View(getContext());
    rule.setBackgroundColor(INK);
    body.addView(rule, new LayoutParams(-1, dp(3)));
    body.addView(title);
    switch (section) {
      case 0:
        trip();
        break;
      case 1:
        display();
        break;
      case 2:
        sound();
        break;
      default:
        connection();
        break;
    }
    scroll.scrollTo(0, 0);
  }

  private void openUpdate() {
    try {
      getContext().startActivity(new Intent(Intent.ACTION_VIEW, UPDATE_APK));
    } catch (ActivityNotFoundException e) {
      Toast.makeText(getContext(), "Open the Subway Board download link on a phone with a browser.",
          Toast.LENGTH_LONG).show();
    }
  }

  private void trip() {
    JSONObject station = selected();
    heading("Station");
    String stationLabel =
        station == null
            ? "Choose a station"
            : station.optString("name") + "  ·  " + station.optString("line", station.optString("area"));
    action(stationLabel + "   ›", this::stationPicker);
    if (station != null) {
      heading("Line");
      choices(station.optJSONArray("routes"), draft.optString("route"), true, station);
      heading("Direction");
      choices(
          directions(station, draft.optString("route")),
          draft.optString("direction"),
          false,
          station);
    }
    stepper("Walk to the platform", "walk", 0, 90);
    stepper("Extra boarding time", "buffer", 0, 30);
  }

  private JSONObject selected() {
    String id = draft.optString("station");
    for (int i = 0; i < catalog.length(); i++) {
      JSONObject s = catalog.optJSONObject(i);
      if (s != null && id.equals(s.optString("id"))) return s;
    }
    return null;
  }

  private JSONArray directions(JSONObject station, String route) {
    JSONObject byRoute = station.optJSONObject("routeDirections");
    JSONArray ds = byRoute == null ? null : byRoute.optJSONArray(route);
    JSONObject labels = station.optJSONObject("directions");
    JSONArray result = new JSONArray();
    for (String dir : new String[] {"N", "S"})
      if (labels != null && labels.has(dir) && contains(ds, dir)) result.put(dir);
    return result;
  }

  private static boolean contains(JSONArray values, String v) {
    for (int i = 0; values != null && i < values.length(); i++)
      if (v.equals(values.optString(i))) return true;
    return false;
  }

  private String routeLabel(String route) {
    if (route.equals("GS")) return "42 St Shuttle";
    if (route.equals("FS")) return "Franklin Shuttle";
    if (route.equals("H")) return "Rockaway Shuttle";
    return route.equals("SI") ? "SIR" : route;
  }

  private void choose(JSONObject station, String route) {
    put("station", station.optString("id"));
    put("route", route);
    JSONArray dirs = directions(station, route);
    if (!contains(dirs, draft.optString("direction"))) put("direction", dirs.optString(0, ""));
    draw();
  }

  private void choices(JSONArray items, String active, boolean routes, JSONObject station) {
    if (items == null || items.length() == 0) {
      note("No boarding direction available for this selection.");
      return;
    }
    LinearLayout strip = line();
    HorizontalScrollView scrollChoices = new HorizontalScrollView(getContext());
    scrollChoices.setHorizontalScrollBarEnabled(true);
    body.addView(scrollChoices);
    scrollChoices.addView(strip);
    for (int i = 0; i < items.length(); i++) {
      String value = items.optString(i);
      String title =
          routes ? routeLabel(value) : station.optJSONObject("directions").optString(value, value);
      boolean chosen = active.equals(value);
      Runnable selectChoice = () -> {
        if (routes) choose(station, value);
        else {
          put("direction", value);
          draw();
        }
      };
      View button = routes ? routeChoice(value, chosen, selectChoice) :
          control(
              title,
              chosen ? INK : SOFT,
              chosen ? WHITE : INK,
              selectChoice);
      button.setSelected(chosen);
      button.setContentDescription(title + (chosen ? ", selected" : ""));
      LayoutParams params = new LayoutParams(-2, dp(48));
      params.rightMargin = dp(5);
      strip.addView(button, params);
    }
  }

  private View routeChoice(String route, boolean chosen, Runnable action) {
    View badge = new View(getContext()) {
      @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        BoardView.drawRouteBullet(canvas, route, getWidth() / 2f, getHeight() / 2f, dp(16));
      }
    };
    badge.setMinimumWidth(dp(52));
    badge.setFocusable(true);
    badge.setBackground(new android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x44787878), shape(chosen ? INK : SOFT), null));
    badge.setOnClickListener(v -> action.run());
    return badge;
  }

  private void stationPicker() {
    Context c = getContext();
    LinearLayout panel = column();
    panel.setBackgroundColor(PAPER);
    panel.setPadding(dp(12), dp(4), dp(12), dp(4));
    panel.setFocusableInTouchMode(true);
    EditText input = field("Search station, borough or line", "");
    panel.addView(input);
    ListView results = new ListView(c);
    panel.addView(results, new LayoutParams(-1, 0, 1));
    List<JSONObject> matches = new ArrayList<>();
    ArrayAdapter<String> rows =
        new ArrayAdapter<String>(c, android.R.layout.simple_list_item_1) {
          @Override
          public View getView(int position, View convert, android.view.ViewGroup parent) {
            TextView v = (TextView) super.getView(position, convert, parent);
            v.setTextColor(INK);
            v.setTextSize(16);
            v.setMinHeight(dp(50));
            v.setSingleLine(false);
            return v;
          }
        };
    results.setAdapter(rows);
    Runnable filter =
        () -> {
          String q = input.getText().toString().trim().toLowerCase(Locale.US);
          List<String> labels = new ArrayList<>();
          matches.clear();
          for (int i = 0; i < catalog.length(); i++) {
            JSONObject station = catalog.optJSONObject(i);
            if (station == null) continue;
            JSONArray routes = station.optJSONArray("routes");
            StringBuilder lines = new StringBuilder();
            if (routes == null || routes.length() == 0) continue;
            for (int j = 0; j < routes.length(); j++) {
              if (j > 0) lines.append(" ");
              lines.append(routeLabel(routes.optString(j)));
            }
            String name =
                station.optString("name")
                    + "   ·   "
                    + lines
                    + "   ·   "
                    + station.optString("area");
            if (!station.optString("line").isEmpty()) name += "   ·   " + station.optString("line");
            if (!name.toLowerCase(Locale.US).contains(q)) continue;
            matches.add(station);
            labels.add(name);
          }
          rows.setNotifyOnChange(false);
          rows.clear();
          rows.addAll(labels);
          rows.notifyDataSetChanged();
          if (matches.isEmpty()) {
            rows.add("No matching station");
            rows.notifyDataSetChanged();
          }
        };
    filter.run();
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            filter.run();
          }

          public void afterTextChanged(Editable e) {}
        });
    AlertDialog dlg =
        new AlertDialog.Builder(c)
            .setTitle("Choose a station")
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .create();
    results.setOnItemClickListener(
        (parent, v, index, id) -> {
          if (index >= matches.size()) return;
          JSONObject s = matches.get(index);
          JSONArray routes = s.optJSONArray("routes");
          String route = draft.optString("route");
          dlg.dismiss();
          choose(s, contains(routes, route) ? route : routes.optString(0));
        });
    dlg.show();
    dlg.getWindow()
        .setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    dlg.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * .91), -1);
  }

  private void display() {
    toggle("Dim overnight", "dim");
    note("Lower brightness from 10 pm to 6 am, New York time.");
    toggle("Reduce motion", "reduceMotion");
    note("Maps: MTA, NYC Open Data and U.S. Census Bureau. Settings icon: Tabler Icons (MIT).");
  }

  private void sound() {
    toggle("Departure chime", "chime");
    if (!draft.optBoolean("chime")) {
      note("The leave cue always appears on the board.");
      return;
    }
    action("Test chime", listener::testChime);
    toggle("Chime for each catchable train", "chimeRepeat");
    note(
        draft.optBoolean("chimeRepeat")
            ? "Ping 2 minutes and 30 seconds before leaving for each train."
            : "Ping 2 minutes and 30 seconds before leaving for one train per window.");
    heading("Days");
    LinearLayout days = line();
    String[] names = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    HorizontalScrollView scroller = new HorizontalScrollView(getContext());
    body.addView(scroller);
    scroller.addView(days);
    for (int i = 0; i < 7; i++) {
      int mask = 1 << i;
      boolean on = (draft.optInt("days") & mask) != 0;
      TextView day =
          control(
              names[i],
              on ? INK : SOFT,
              on ? WHITE : INK,
              () -> {
                put("days", draft.optInt("days") ^ mask);
                draw();
              });
      day.setSelected(on);
      LayoutParams p = new LayoutParams(-2, dp(48));
      p.rightMargin = dp(4);
      days.addView(day, p);
    }
    heading("Commute hours  ·  New York");
    time("From", "start");
    time("Until", "end");
  }

  private void time(String label, String key) {
    int minutes = draft.optInt(key);
    String when =
        String.format(
            Locale.US,
            "%d:%02d %s",
            (minutes / 60 + 11) % 12 + 1,
            minutes % 60,
            minutes < 720 ? "am" : "pm");
    action(
        label + "   " + when,
        () ->
            new TimePickerDialog(
                    getContext(),
                    (v, h, m) -> {
                      put(key, h * 60 + m);
                      draw();
                    },
                    minutes / 60,
                    minutes % 60,
                    false)
                .show());
  }

  private void connection() {
    heading("Get arrivals from");
    LinearLayout sources = line();
    body.addView(sources);
    for (boolean phone : new boolean[]{true, false}) {
      boolean selected = phone == usePhone;
      TextView option = control(phone ? "This phone" : "Home server",
          selected ? INK : SOFT, selected ? WHITE : INK, () -> {
            captureAddress();
            usePhone = phone;
            draw();
            listener.changeSource(serviceAddress());
          });
      option.setSelected(selected);
      LayoutParams p = new LayoutParams(-2, dp(48));
      p.rightMargin = dp(5);
      sources.addView(option, p);
    }
    if (usePhone) {
      note("This phone gets MTA arrivals and service changes over Wi-Fi. Weather comes from the National Weather Service.");
    } else {
      heading("Board service address");
      serverInput = field("Host or IP address", draft.optString("server"));
      serverInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
      body.addView(serverInput, new LayoutParams(-1, dp(48)));
      action("Test connection", () -> {
        if (requireAddress()) listener.loadStations(serviceAddress());
      });
    }
    action(
        "Open Wi-Fi settings",
        () -> getContext().startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
  }

  private boolean requireAddress() {
    if (usePhone || !serviceAddress().isEmpty()) return true;
    select(3);
    serverInput.setError("Enter the home server address.");
    serverInput.requestFocus();
    return false;
  }

  private void save() {
    captureAddress();
    if (!requireAddress()) return;
    if (draft.optBoolean("chime")
        && (draft.optInt("days") == 0 || draft.optInt("start") == draft.optInt("end"))) {
      select(2);
      Toast.makeText(getContext(), "Select a day and two different times.", Toast.LENGTH_LONG)
          .show();
      return;
    }
    JSONObject station = selected();
    if (station == null
        || !contains(station.optJSONArray("routes"), draft.optString("route"))
        || !contains(directions(station, draft.optString("route")), draft.optString("direction"))) {
      select(0);
      Toast.makeText(
              getContext(), "Choose a station, line and boarding direction.", Toast.LENGTH_LONG)
          .show();
      return;
    }
    if (usePhone) put("server", "");
    listener.save(draft);
  }

  private void toggle(String title, String key) {
    boolean on = draft.optBoolean(key);
    TextView choice =
        control(
            title + "     " + (on ? "On" : "Off"),
            on ? INK : SOFT,
            on ? WHITE : INK,
            () -> {
              put(key, !on);
              draw();
            });
    choice.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
    choice.setSelected(on);
    append(choice);
  }

  private void stepper(String label, String key, int min, int max) {
    heading(label);
    LinearLayout row = line();
    body.addView(row);
    int value = draft.optInt(key);
    TextView count = label(value + " min", 20, INK, true);
    count.setGravity(Gravity.CENTER);
    final TextView minus = control("−", SOFT, INK, () -> adjust(key, min, max, -1, count));
    final TextView plus = control("+", SOFT, INK, () -> adjust(key, min, max, 1, count));
    minus.setContentDescription("Decrease " + label);
    plus.setContentDescription("Increase " + label);
    row.addView(minus, new LayoutParams(dp(52), dp(48)));
    row.addView(count, new LayoutParams(dp(89), dp(48)));
    row.addView(plus, new LayoutParams(dp(52), dp(48)));
  }

  private void adjust(String key, int min, int max, int delta, TextView count) {
    int newValue = Math.min(max, Math.max(min, draft.optInt(key) + delta));
    put(key, newValue);
    count.setText(newValue + " min");
  }

  private EditText field(String hint, String value) {
    EditText field = new EditText(getContext());
    field.setImeOptions(
        android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            | android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
    field.setTextColor(INK);
    field.setHintTextColor(MUTED);
    field.setTextSize(17);
    field.setSingleLine();
    field.setHint(hint);
    field.setText(value);
    field.setPadding(dp(12), 0, dp(12), 0);
    field.setMinHeight(dp(48));
    field.setBackground(shape(WHITE));
    return field;
  }

  private void heading(String s) {
    TextView text = label(s, 14, MUTED, true);
    text.setPadding(0, dp(12), 0, dp(5));
    body.addView(text);
  }

  private void note(String s) {
    TextView text = label(s, 15, MUTED, false);
    text.setPadding(0, dp(5), 0, dp(8));
    body.addView(text);
  }

  private void action(String title, Runnable task) {
    append(control(title, SOFT, INK, task));
  }

  private void append(View view) {
    LayoutParams p = new LayoutParams(-1, dp(48));
    p.topMargin = dp(5);
    body.addView(view, p);
  }

  private TextView control(String s, int bg, int fg, Runnable task) {
    TextView v = label(s, 16, fg, true);
    v.setMinWidth(dp(48));
    v.setGravity(Gravity.CENTER);
    v.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x339e9d90), shape(bg), null));
    v.setPadding(dp(12), 0, dp(12), 0);
    v.setFocusable(true);
    v.setOnClickListener(b -> task.run());
    return v;
  }

  private GradientDrawable shape(int color) {
    GradientDrawable d = new GradientDrawable();
    d.setCornerRadius(dp(2));
    d.setColor(color);
    return d;
  }

  private TextView label(String text, int size, int color, boolean bold) {
    TextView v = new TextView(getContext());
    v.setText(text);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
    return v;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(getContext());
    l.setOrientation(VERTICAL);
    return l;
  }

  private LinearLayout line() {
    LinearLayout l = new LinearLayout(getContext());
    l.setOrientation(HORIZONTAL);
    l.setBaselineAligned(false);
    return l;
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }
}
