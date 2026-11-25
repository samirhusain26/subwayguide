package nyc.subway.board;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;

/** Pure decision helpers for the persistent arrivals board. */
public final class Decision {
  public static final class Train {
    public final String id;
    public final long time;
    public final String destination;

    public final String route, onward;

    public Train(String id, long time, String destination) {
      this(id, time, destination, "", "");
    }

    public Train(String id, long time, String destination, String route, String onward) {
      this.route = route == null ? "" : route;
      this.onward = onward == null ? "" : onward;
      this.id = id == null ? "" : id.trim();
      this.time = time;
      this.destination = destination;
    }
  }

  public final Train primary;
  public final String headline;
  public final String detail;
  public final String next;
  public final String targetId;
  /** Position of the reachable train in the displayed arrivals, or -1 if absent. */
  public final int targetIndex;
  public final long targetTime;
  public final String targetDestination;
  public final String targetRoute;
  public final String followingId;
  public final long followingTime;
  public final String followingDestination;
  public final String followingRoute;
  public final long alternateLeaveSeconds;
  public final long leaveSeconds;
  public final boolean leaveNow;
  public final boolean live;

  private Decision(
      String headline,
      String detail,
      String next,
      String targetId,
      int targetIndex,
      long targetTime, String targetDestination, String targetRoute, String followingId, long followingTime,
      String followingDestination, String followingRoute, long alternateLeaveSeconds,
      long leaveSeconds, boolean leaveNow,
      boolean live, Train primary) {
    this.primary = primary;
    this.headline = headline;
    this.detail = detail;
    this.next = next;
    this.targetId = targetId;
    this.targetIndex = targetIndex;
    this.targetTime = targetTime;
    this.targetDestination = targetDestination;
    this.targetRoute = targetRoute;
    this.followingId = followingId;
    this.followingTime = followingTime;
    this.followingDestination = followingDestination;
    this.followingRoute = followingRoute;
    this.alternateLeaveSeconds = alternateLeaveSeconds;
    this.leaveSeconds = leaveSeconds;
    this.leaveNow = leaveNow;
    this.live = live;
  }

  /**
   * Selects the earliest train that can still be reached on foot. Times are epoch seconds. A feed
   * is considered live only when status is "ok" and its timestamp is no more than 60 seconds old.
   */
  public static Decision evaluate(
      long nowSecs, long feedTime, String status, List<Train> trains, int walk, int buffer) {
    long age = nowSecs - feedTime;
    boolean fresh = "ok".equals(status) && age >= 0 && age <= 60;
    if (!fresh) {
      String label;
      if ("unavailable".equals(status)) label = "Arrivals unavailable";
      else if ("stale".equals(status) || ("ok".equals(status) && age > 60))
        label = "Arrivals delayed";
      else label = "Waiting for live arrivals";
      return new Decision(
          label, "Countdowns are hidden until live data returns.", "", null, -1, 0, "", "", null, 0, "", "", 0, 0, false, false, null);
    }
    long walkSecs = Math.max(0, walk) * 60L;
    long cushionSecs = Math.max(0, buffer) * 60L;
    List<Train> upcoming = new ArrayList<Train>();
    if (trains != null) {
      for (Train train : trains) {
        if (train != null && train.time > nowSecs) upcoming.add(train);
      }
    }
    Collections.sort(
        upcoming,
        new Comparator<Train>() {
          @Override
          public int compare(Train a, Train b) {
            if (a.time < b.time) return -1;
            if (a.time > b.time) return 1;
            int route = a.route.compareTo(b.route);
            return route != 0 ? route : a.id.trim().compareTo(b.id.trim());
          }
        });
    StringBuilder predictions = new StringBuilder();
    int predictionCount = Math.min(3, upcoming.size());
    for (int i = 0; i < predictionCount; i++) {
      Train train = upcoming.get(i);
      if (predictions.length() > 0) predictions.append('\n');
      predictions.append(((train.time - nowSecs + 59L) / 60L)).append(" min");
      if (!train.route.isEmpty()) predictions.append(" · ").append(train.route);
      String destination = cleanDestination(train.destination);
      if (destination.length() > 0) predictions.append(" · ").append(destination);
    }
    String next = predictions.toString();
    if (upcoming.isEmpty()) {
      return new Decision("No upcoming trains", "Live data is current.", "", null, -1, 0, "", "", null, 0, "", "", 0, 0, false, true, null);
    }

    Train reachable = null;
    int targetIndex = -1;
    for (int i = 0; i < upcoming.size(); i++) {
      Train train = upcoming.get(i);
      if (train.time < nowSecs + walkSecs) continue;
      reachable = train;
      if (i < predictionCount) targetIndex = i;
      break;
    }
    if (reachable == null) {
      Train first = upcoming.get(0);
      String firstDestination = cleanDestination(first.destination);
      String detail =
          "Next train in " + ((first.time - nowSecs + 59L) / 60L) + " min";
      if (firstDestination.length() > 0) detail += " · " + firstDestination;
      return new Decision("No reachable train", detail, next, null, -1, 0, "", "", null, 0, "", "", 0, 0, false, true, null);
    }

    long remaining = reachable.time - nowSecs;
    long etaMinutes = (remaining + 59L) / 60L;
    long leaveSlack = remaining - walkSecs - cushionSecs;
    boolean leave = cushionSecs > 0 ? leaveSlack <= 0 : leaveSlack <= 59L;
    long leaveMinutes = Math.max(0, leaveSlack / 60L);
    String eta = etaMinutes + " min";
    String headline =
        leave
            ? "Leave now"
            : "Leave in " + (leaveMinutes == 0 ? "<1" : Long.toString(leaveMinutes)) + " min";
    String destination = cleanDestination(reachable.destination);
    String detail = "Train in " + eta + (destination.length() == 0 ? "" : " · " + destination);
    Train following = null;
    if (!reachable.id.isEmpty()) {
      for (Train candidate : upcoming) {
        if (candidate.time < reachable.time || candidate.id.isEmpty()
            || sameTrain(candidate, reachable)) continue;
        following = candidate;
        break;
      }
    }
    long alternate = following == null ? 0 : following.time - nowSecs - walkSecs - cushionSecs;
    if (alternate <= (cushionSecs == 0 ? 59 : 0)) alternate = 0;
    return new Decision(headline, detail, next, reachable.id.isEmpty() ? null : reachable.id,
        targetIndex, reachable.time, cleanDestination(reachable.destination), reachable.route,
        following == null ? null : following.id, following == null ? 0 : following.time,
        following == null ? "" : cleanDestination(following.destination),
        following == null ? "" : following.route, alternate, leaveSlack, leave, true, reachable);
  }

  private static String cleanDestination(String destination) {
    return destination == null ? "" : destination.trim();
  }

  private static boolean sameTrain(Train a, Train b) {
    return a.id.equals(b.id) && a.route.equals(b.route);
  }

  /** Returns a stable key for the active New York local-time chime window, or null. */
  public static String chimeWindow(long nowSeconds, int days, int startMinutes, int endMinutes) {
    if ((days & 0x7f) == 0
        || (days & ~0x7f) != 0
        || startMinutes < 0
        || startMinutes >= 1440
        || endMinutes < 0
        || endMinutes >= 1440
        || startMinutes == endMinutes) return null;
    TimeZone zone = TimeZone.getTimeZone("America/New_York");
    Calendar now = Calendar.getInstance(zone);
    now.setTimeInMillis(nowSeconds * 1000L);
    int minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    int day = now.get(Calendar.DAY_OF_WEEK); // Sunday=1
    int mondayBit = (day + 5) % 7;
    Calendar anchor = (Calendar) now.clone();
    boolean active;
    if (startMinutes < endMinutes) {
      active = minuteOfDay >= startMinutes && minuteOfDay < endMinutes;
    } else if (minuteOfDay >= startMinutes) {
      active = true;
    } else if (minuteOfDay < endMinutes) {
      active = true;
      anchor.add(Calendar.DAY_OF_MONTH, -1);
      mondayBit = (mondayBit + 6) % 7;
    } else {
      active = false;
    }
    if (!active || (days & (1 << mondayBit)) == 0) return null;
    return String.format(
        java.util.Locale.US,
        "%04d-%02d-%02d:%04d-%04d",
        anchor.get(Calendar.YEAR),
        anchor.get(Calendar.MONTH) + 1,
        anchor.get(Calendar.DAY_OF_MONTH),
        startMinutes,
        endMinutes);
  }
}
