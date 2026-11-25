package nyc.subway.board;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The four sections share a draft. Only Save publishes the new configuration. */
public final class SettingsScreen extends LinearLayout {
  private static final String[] TRIP_FIELDS = {"station", "route", "routes", "direction", "tripDirection",
      "directionsByRoute", "destination", "destinationId", "walk", "buffer"};
  public interface Listener {
    void save(JSONObject settings);

    void cancel();

    void testChime();

    void loadStations(String server);

    void changeSource(String server);

    void downloadUpdate();

    void installUpdate();
  }

  private static final int PAPER = 0xfff6f6f4, INK = 0xff101010, MUTED = 0xff515459;
  private static final int SOFT = 0xffe5e6e4, WHITE = 0xffffffff;
  private static final String[] TABS = {"Trip", "Display", "Sound", "Connection"};
  private final Listener listener;
  private final JSONObject draft;
  private final TripChoices tripChoices;
  private JSONArray catalog;
  private final LinearLayout nav, body;
  private final ScrollView scroll;
  private int section;
  private EditText serverInput;
  private boolean usePhone;
  private String updateStatus = "Check for and download the latest signed release.";
  private boolean updateReady;
  private boolean updateBusy;
  private Spinner destinationInput;
  private List<String> destinationValues;
  private List<String> destinationNames;
  private boolean restoreServerFocus;

  public SettingsScreen(
      Context context, JSONArray stations, JSONObject current, Listener listener) {
    this(context, stations, current, listener, null);
  }

  public SettingsScreen(
      Context context, JSONArray stations, JSONObject current, Listener listener, JSONObject savedUiState) {
    super(context);
    this.listener = listener;
    this.catalog = stations;
    this.tripChoices = new TripChoices(context);
    try {
      JSONObject source = savedUiState != null && savedUiState.optJSONObject("draft") != null
          ? savedUiState.optJSONObject("draft") : current;
      draft = new JSONObject(source.toString());
    } catch (JSONException e) {
      throw new IllegalArgumentException("Invalid preferences", e);
    }
    if (savedUiState != null) {
      section = Math.max(0, Math.min(4, savedUiState.optInt("section", 0)));
      restoreScrollY = Math.max(0, savedUiState.optInt("scrollY", 0));
      restoreServerFocus = savedUiState.optBoolean("serverFocused");
    }
    JSONObject currentStation = selected();
    if (currentStation != null && draft.optJSONObject("directionsByRoute") == null)
      tripChoices.migrate(draft, currentStation);
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
    usePhone = savedUiState != null && savedUiState.has("usePhone")
        ? savedUiState.optBoolean("usePhone") : draft.optString("server").isEmpty();
    draw();
  }

  private int restoreScrollY;

  public JSONObject uiState() {
    JSONObject state = new JSONObject();
    try {
      captureAddress();
      captureTripFields();
      state.put("draft", new JSONObject(draft.toString()));
      state.put("section", section);
      state.put("scrollY", scroll.getScrollY());
      state.put("usePhone", usePhone);
      state.put("serverFocused", serverInput != null && serverInput.hasFocus());
    } catch (JSONException ignored) { }
    return state;
  }

  public JSONObject draft() {
    try { return new JSONObject(draft.toString()); }
    catch (JSONException impossible) { return new JSONObject(); }
  }

  public void setStations(JSONArray stations) {
    captureAddress();
    restoreScrollY = scroll.getScrollY();
    catalog = stations;
    reconcileTripWithCatalog();
    draw();
  }

  private void reconcileTripWithCatalog() {
    JSONObject station = selected();
    if (station == null) return;
    JSONArray offered = station.optJSONArray("routes");
    JSONArray previous = selectedRoutes();
    JSONObject mapped = tripChoices.directionsFor(station,
        draft.optString("tripDirection", draft.optString("direction")), previous);
    JSONArray retained = new JSONArray();
    JSONObject keptDirections = new JSONObject();
    for (int i = 0; i < previous.length(); i++) {
      String route = previous.optString(i);
      String direction = mapped.optString(route);
      if (!contains(offered, route) || direction.isEmpty()) continue;
      retained.put(route);
      try { keptDirections.put(route, direction); } catch (JSONException ignored) { }
    }
    put("routes", retained);
    put("route", retained.optString(0, ""));
    put("directionsByRoute", keptDirections);
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
    restoreScrollY = 0;
    draw();
  }

  private void draw() {
    captureTripFields();
    destinationInput = null;
    destinationValues = null;
    destinationNames = null;
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
    nav.addView(control("Update app", INK, WHITE, () -> select(4)), new LayoutParams(-1, dp(48)));
    if (section == 4) {
      updatePage();
      restoreScroll();
      return;
    }
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
    restoreScroll();
  }

  private void restoreScroll() {
    final int y = restoreScrollY;
    restoreScrollY = 0;
    scroll.post(() -> scroll.scrollTo(0, y));
  }

  void setUpdateState(String status, boolean ready) {
    setUpdateState(status, ready, false);
  }

  void setUpdateState(String status, boolean ready, boolean busy) {
    updateStatus = status;
    updateReady = ready;
    updateBusy = busy;
    if (section == 4) draw();
  }

  private void updatePage() {
    View rule = new View(getContext());
    rule.setBackgroundColor(INK);
    body.addView(rule, new LayoutParams(-1, dp(3)));
    body.addView(label("Update app", 29, INK, true));
    note(updateStatus);
    if (!updateBusy) {
      if (updateReady) {
        action("Install update", listener::installUpdate);
        action("Check and download latest update", listener::downloadUpdate);
      } else action("Download latest update", listener::downloadUpdate);
    }
    if (updateReady) note("The Android installer will ask you to confirm the update.");
    if (android.os.Build.VERSION.SDK_INT < 26)
      note("Android 5.1: if Android blocks installation, open Settings → Security → Unknown sources, enable it, then try again.");
  }

  private void trip() {
    JSONObject station = selected();
    namedTrips();
    String stationLabel =
        station == null
            ? "Choose a station"
            : station.optString("name") + "  ·  " + station.optString("line", station.optString("area"));
    if (station != null && station.optJSONArray("routes") != null
        && station.optJSONArray("routes").length() == 1) {
      LinearLayout row = line();
      body.addView(row, new LayoutParams(-1, dp(48)));
      TextView stationButton = control(stationLabel + "   ›", SOFT, INK, this::stationPicker);
      row.addView(stationButton, new LayoutParams(0, dp(48), 1));
      String route = station.optJSONArray("routes").optString(0);
      row.addView(routeChoice(route, contains(selectedRoutes(), route), () -> choose(station, route)),
          new LayoutParams(dp(56), dp(48)));
    } else {
      heading("Station");
      action(stationLabel + "   ›", this::stationPicker);
    }
    if (station != null) {
      JSONArray routes = selectedRoutes();
      heading("Direction");
      choices(tripChoices.namedDirections(station), draft.optString("tripDirection", draft.optString("direction")), false, station);
      if (station.optJSONArray("routes").length() > 1) {
        heading("Lines");
        choices(station.optJSONArray("routes"), "", true, station);
      }
      if (tripChoices.requiresSemanticDirection(station)
          && !contains(tripChoices.namedDirections(station), draft.optString("tripDirection")))
        note("Choose a direction to see eligible lines.");
      if (routes.length() > 1 || tripChoices.requiresSemanticDirection(station)) {
        String onward = tripChoices.onwardSummary(station, draft);
        if (!onward.isEmpty()) note(onward);
      }
    }
    stepper("Walk to the platform", "walk", 0, 90);
    stepper("Extra boarding time", "buffer", 0, 30);
    if (station != null) destinationChoice(station);
    action("Save this trip…", this::saveNamedTrip);
  }

  private void destinationChoice(JSONObject station) {
    heading("Destination (optional)");
    JSONArray terminals = tripChoices.destinationsFor(station, draft);
    List<String> values = new ArrayList<>();
    List<String> names = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    values.add("");
    names.add("");
    labels.add("Any destination");
    for (int i = 0; i < terminals.length(); i++) {
      JSONObject terminal = terminals.optJSONObject(i);
      if (terminal == null) continue;
      values.add(terminal.optString("terminalId"));
      names.add(terminal.optString("terminalName"));
      labels.add(terminal.optString("terminalName"));
    }
    String savedId = draft.optString("destinationId");
    String saved = draft.optString("destination");
    int index = savedId.isEmpty() ? names.indexOf(saved) : values.indexOf(savedId);
    if ((!savedId.isEmpty() || !saved.isEmpty()) && index < 1) {
      values.add(savedId);
      names.add(saved);
      labels.add("(unlisted) " + saved);
      index = values.size() - 1;
    }
    ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, labels);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    destinationValues = values;
    destinationNames = names;
    destinationInput = new Spinner(getContext());
    destinationInput.setAdapter(adapter);
    destinationInput.setSelection(Math.max(0, index));
    body.addView(destinationInput, new LayoutParams(-1, dp(48)));
    if (terminals.length() == 0) note("No scheduled destinations for this selection.");
  }

  private void namedTrips() {
    JSONArray trips = draft.optJSONArray("namedTrips");
    if (trips == null || trips.length() == 0) return;
    heading("Saved trips");
    LinearLayout strip = line();
    HorizontalScrollView row = new HorizontalScrollView(getContext());
    body.addView(row);
    row.addView(strip);
    for (int i = 0; i < trips.length(); i++) {
      JSONObject trip = trips.optJSONObject(i);
      if (trip == null) continue;
      String name = trip.optString("name");
      TextView button = control(name, SOFT, INK, () -> applyNamedTrip(trip));
      strip.addView(button, new LayoutParams(-2, dp(48)));
    }
    action("Manage saved trips", this::manageNamedTrips);
  }

  private void manageNamedTrips() {
    JSONArray trips = draft.optJSONArray("namedTrips");
    if (trips == null || trips.length() == 0) return;
    List<String> names = new ArrayList<>();
    for (int i = 0; i < trips.length(); i++) {
      JSONObject trip = trips.optJSONObject(i);
      if (trip != null) names.add(trip.optString("name"));
    }
    new AlertDialog.Builder(getContext()).setTitle("Remove a saved trip")
        .setItems(names.toArray(new String[0]), (dialog, index) -> {
          JSONArray updated = new JSONArray();
          for (int i = 0; i < trips.length(); i++) {
            JSONObject trip = trips.optJSONObject(i);
            if (trip != null && !names.get(index).equals(trip.optString("name"))) updated.put(trip);
          }
          put("namedTrips", updated);
          draw();
        }).setNegativeButton("Done", null).show();
  }

  private void applyNamedTrip(JSONObject trip) {
    for (String key : TRIP_FIELDS) {
      if (trip.has(key)) put(key, trip.opt(key));
      else draft.remove(key);
    }
    JSONObject station = selected();
    if (station != null) tripChoices.migrate(draft, station);
    destinationInput = null;
    draw();
  }

  private void saveNamedTrip() {
    if (!requireTrip()) return;
    EditText input = field("Trip name", "");
    new AlertDialog.Builder(getContext()).setTitle("Save this trip")
        .setView(input).setNegativeButton("Cancel", null)
        .setPositiveButton("Save", (d, w) -> {
          String name = input.getText().toString().trim();
          if (name.isEmpty()) return;
          captureTripFields();
          JSONObject snapshot = new JSONObject();
          try {
            for (String key : TRIP_FIELDS)
              if (draft.has(key)) snapshot.put(key, draft.opt(key));
            JSONArray trips = draft.optJSONArray("namedTrips");
            JSONArray updated = new JSONArray();
            for (int i = 0; trips != null && i < trips.length(); i++) {
              JSONObject old = trips.optJSONObject(i);
              if (old != null && !name.equalsIgnoreCase(old.optString("name"))) updated.put(old);
            }
            snapshot.put("name", name);
            updated.put(snapshot);
            put("namedTrips", updated);
            draw();
          } catch (JSONException ignored) { }
        }).show();
  }

  private void captureTripFields() {
    if (destinationInput != null) {
      int position = destinationInput.getSelectedItemPosition();
      put("destinationId", destinationValues == null || position < 0 || position >= destinationValues.size()
          ? "" : destinationValues.get(position));
      put("destination", destinationNames == null || position < 1 || position >= destinationNames.size()
          ? "" : destinationNames.get(position));
    }
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

  private void choose(JSONObject station, String route) {
    captureTripFields();
    put("station", station.optString("id"));
    JSONArray routes = selectedRoutes();
    if (contains(routes, route)) {
      JSONArray kept = new JSONArray();
      for (int i = 0; i < routes.length(); i++)
        if (!route.equals(routes.optString(i))) kept.put(routes.optString(i));
      routes = kept;
    } else {
      JSONObject allDirections = tripChoices.directionsFor(
          station, draft.optString("tripDirection", draft.optString("direction")), station.optJSONArray("routes"));
      String direction = allDirections.optString(route);
      if (direction.isEmpty() || !contains(directions(station, route), direction)) return;
      routes.put(route);
    }
    put("routes", routes);
    put("route", routes.optString(0, ""));
    put("directionsByRoute", tripChoices.directionsFor(
        station, draft.optString("tripDirection", draft.optString("direction")), routes));
    clearUnavailableDestination(station);
    destinationInput = null;
    draw();
  }

  private void clearUnavailableDestination(JSONObject station) {
    String destinationId = draft.optString("destinationId");
    String destinationName = draft.optString("destination");
    if (destinationId.isEmpty() && destinationName.isEmpty()) return;
    JSONArray options = tripChoices.destinationsFor(station, draft);
    for (int i = 0; i < options.length(); i++) {
      JSONObject option = options.optJSONObject(i);
      if (option != null && (!destinationId.isEmpty()
          ? destinationId.equals(option.optString("terminalId"))
          : destinationName.equalsIgnoreCase(option.optString("terminalName")))) return;
    }
    put("destination", "");
    put("destinationId", "");
  }

  private void chooseStation(JSONObject station) {
    boolean changedStation = !station.optString("id").equals(draft.optString("station"));
    captureTripFields();
    destinationInput = null;
    if (changedStation) { put("destination", ""); put("destinationId", ""); }
    put("station", station.optString("id"));
    JSONArray offered = station.optJSONArray("routes");
    JSONArray selected = selectedRoutes();
    String named = draft.optString("tripDirection", draft.optString("direction"));
    JSONArray choices = tripChoices.namedDirections(station);
    if (!contains(choices, named)) {
      String legacy = draft.optString("direction");
      named = contains(choices, legacy) ? legacy : choices.length() == 1 ? choices.optString(0) : "";
    }
    put("tripDirection", named);
    JSONArray compatible;
    if (tripChoices.requiresSemanticDirection(station)) {
      JSONObject maps = tripChoices.directionsFor(station, named, selected);
      compatible = new JSONArray();
      for (int i = 0; i < selected.length(); i++)
        if (maps.has(selected.optString(i))) compatible.put(selected.optString(i));
    } else {
      compatible = compatibleRoutes(station, selected, named);
    }
    if (compatible.length() == 0 && offered != null && offered.length() == 1)
      compatible.put(offered.optString(0));
    put("routes", compatible);
    put("route", compatible.optString(0, ""));
    put("directionsByRoute", tripChoices.directionsFor(station, named, compatible));
    draw();
  }

  private JSONArray selectedRoutes() {
    JSONArray routes = draft.optJSONArray("routes");
    if (routes != null) {
      JSONArray copy = new JSONArray();
      for (int i = 0; i < routes.length(); i++) copy.put(routes.optString(i));
      return copy;
    }
    JSONArray legacy = new JSONArray();
    String route = draft.optString("route");
    if (!route.isEmpty()) legacy.put(route);
    return legacy;
  }

  private JSONArray compatibleRoutes(JSONObject station, JSONArray selected, String direction) {
    List<String> requested = new ArrayList<>();
    for (int i = 0; selected != null && i < selected.length(); i++)
      requested.add(selected.optString(i));
    Map<String, Set<String>> byRoute = new LinkedHashMap<>();
    JSONObject routeDirections = station.optJSONObject("routeDirections");
    if (routeDirections != null) {
      java.util.Iterator<String> routes = routeDirections.keys();
      while (routes.hasNext()) {
        String route = routes.next();
        JSONArray values = routeDirections.optJSONArray(route);
        Set<String> directions = new LinkedHashSet<>();
        for (int i = 0; values != null && i < values.length(); i++)
          directions.add(values.optString(i));
        byRoute.put(route, directions);
      }
    }
    JSONArray result = new JSONArray();
    for (String route : MultiRoutePolicy.compatibleRoutes(requested, byRoute, direction))
      result.put(route);
    return result;
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
      String title = routes ? BoardView.routeName(value) : tripChoices.label(station, value);
      boolean chosen = routes ? contains(selectedRoutes(), value) : active.equals(value);
      Runnable selectChoice = () -> {
        if (routes) choose(station, value);
        else {
          captureTripFields();
          put("tripDirection", value);
          if (value.equals("N") || value.equals("S")) put("direction", value);
          JSONObject mapping = tripChoices.directionsFor(station, value, selectedRoutes());
          put("directionsByRoute", mapping);
          JSONArray compatible = new JSONArray();
          JSONArray selected = selectedRoutes();
          for (int r = 0; r < selected.length(); r++)
            if (mapping.has(selected.optString(r))) compatible.put(selected.optString(r));
          put("routes", compatible);
          put("route", compatible.optString(0, ""));
          clearUnavailableDestination(station);
          destinationInput = null;
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
      boolean directionAvailable = !routes || tripChoices.directionsFor(station, draft.optString("tripDirection"),
          new JSONArray().put(value)).has(value);
      if (!directionAvailable) {
        button.setEnabled(false);
        button.setAlpha(.4f);
      }
      button.setContentDescription(title + (chosen ? ", selected" : "")
          + (directionAvailable ? "" : ", unavailable in selected direction"));
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
    badge.setContentDescription(BoardView.routeName(route) + (chosen ? ", selected" : ", not selected"));
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
          String q = input.getText().toString().trim();
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
              lines.append(BoardView.routeName(routes.optString(j)));
            }
            String name =
                station.optString("name")
                    + "   ·   "
                    + lines
                    + "   ·   "
                    + station.optString("area");
            if (!station.optString("line").isEmpty()) name += "   ·   " + station.optString("line");
            if (!matchesStationQuery(q, name, routes)) continue;
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
          dlg.dismiss();
          chooseStation(s);
        });
    dlg.show();
    dlg.getWindow()
        .setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    dlg.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * .91), -1);
  }

  static boolean matchesStationQuery(String query, String fields, JSONArray routes) {
    String normalized = normalizeSearch(query);
    if (normalized.isEmpty()) return true;
    String haystack = normalizeSearch(fields);
    String[] words = normalized.split(" ");
    for (String word : words) {
      if (word.isEmpty()) continue;
      boolean routeToken = false;
      for (int i = 0; routes != null && i < routes.length(); i++)
        if (normalizeSearch(BoardView.routeName(routes.optString(i))).equals(word)) routeToken = true;
      boolean fieldToken = false;
      for (String token : haystack.split(" ")) if (token.equals(word)) fieldToken = true;
      if (!fieldToken && !routeToken) return false;
    }
    return true;
  }

  private static String normalizeSearch(String value) {
    String text = value == null ? "" : value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
    text = text.replaceAll("\\bsq\\b", "square").replaceAll("\\bst\\b", "street");
    text = text.replaceAll("\\bave\\b", "avenue").replaceAll("\\bav\\b", "avenue");
    return text.replaceAll("\\s+", " ");
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
    toggle("Repeat for the next recommended train", "chimeRepeat");
    note(
        draft.optBoolean("chimeRepeat")
            ? "Repeat both reminders for the next train recommended by the board."
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
      final TextView[] ref = new TextView[1];
      TextView day =
          control(
              names[i],
              on ? INK : SOFT,
              on ? WHITE : INK,
              () -> {
                put("days", draft.optInt("days") ^ mask);
                boolean enabled = (draft.optInt("days") & mask) != 0;
                ref[0].setSelected(enabled);
                ref[0].setTextColor(enabled ? WHITE : INK);
                ref[0].setBackground(shape(enabled ? INK : SOFT));
              });
      ref[0] = day;
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
      if (restoreServerFocus) {
        restoreServerFocus = false;
        serverInput.requestFocus();
        serverInput.setSelection(serverInput.length());
      }
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
    captureTripFields();
    if (!requireAddress()) return;
    if (draft.optBoolean("chime")
        && (draft.optInt("days") == 0 || draft.optInt("start") == draft.optInt("end"))) {
      select(2);
      Toast.makeText(getContext(), "Select a day and two different times.", Toast.LENGTH_LONG)
          .show();
      return;
    }
    if (!requireTrip()) return;
    if (usePhone) put("server", "");
    listener.save(draft);
  }

  private boolean requireTrip() {
    JSONObject station = selected();
    JSONArray routes = selectedRoutes();
    JSONObject expected = tripChoices.directionsFor(station, draft.optString("tripDirection"), routes);
    boolean valid = station != null && routes.length() > 0;
    for (int i = 0; valid && i < routes.length(); i++) {
      String route = routes.optString(i);
      valid = contains(station.optJSONArray("routes"), route) && expected.has(route)
          && expected.optString(route).equals(TripChoices.platformDirection(draft, route));
    }
    if (!valid) {
      select(0);
      Toast.makeText(getContext(), "Choose a station, line and boarding direction.", Toast.LENGTH_LONG).show();
    }
    return valid;
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
