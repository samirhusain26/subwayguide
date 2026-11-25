package nyc.subway.board;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.TimeZone;

/** Plain Java assertions; run with -ea. */
public final class DecisionTest {
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static long ny(int year, int month, int day, int hour, int minute) {
    Calendar c = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
    c.clear();
    c.set(year, month - 1, day, hour, minute, 0);
    return c.getTimeInMillis() / 1000L;
  }

  public static void main(String[] args) {
    long now = 1_000_000L;
    Decision d =
        Decision.evaluate(
            now,
            now,
            "ok",
            Arrays.asList(
                new Decision.Train("too-soon", now + 9 * 60L, "Uptown"),
                new Decision.Train("next", now + 13 * 60L, "Uptown")),
            10,
            0);
    check("next".equals(d.targetId), "skip unreachable arrival");
    check(d.targetIndex == 1, "point to the correct visible train");
    check("Leave in 3 min".equals(d.headline), "show actionable walk-adjusted departure");
    check(d.live && !d.leaveNow, "fresh wait is live and not yet leave-now");
    check(
        "9 min · Uptown\n13 min · Uptown".equals(d.next),
        "show first arrivals, including earlier unreachable train");
    check(
        "Train in 13 min · Uptown".equals(d.detail),
        "detail names the selected target consistently");

    Decision afterThree = Decision.evaluate(now, now, "ok", Arrays.asList(
        new Decision.Train("a", now + 401, "South Ferry"),
        new Decision.Train("b", now + 482, "South Ferry"),
        new Decision.Train("c", now + 599, "South Ferry"),
        new Decision.Train("d", now + 600, "South Ferry")), 10, 0);
    check("d".equals(afterThree.targetId) && afterThree.targetIndex == -1,
        "a fourth target must not mark the third arrival even when both round to ten minutes");

    Decision branching = Decision.evaluate(now, now, "ok", Arrays.asList(
        new Decision.Train("early-1", now + 60, ""),
        new Decision.Train("early-2", now + 120, ""),
        new Decision.Train("early-3", now + 180, ""),
        new Decision.Train("primary", now + 780, "Far Rockaway"),
        new Decision.Train("primary", now + 800, "Far Rockaway"),
        new Decision.Train("", now + 810, "Unknown"),
        new Decision.Train("following", now + 1260, "Ozone Park")), 10, 1);
    check(branching.targetTime == now + 780 && branching.leaveSeconds == 120,
        "structured primary survives the platform list limit");
    check("following".equals(branching.followingId) && branching.followingTime == now + 1260
        && branching.alternateLeaveSeconds == 600, "use the next proven distinct train");
    check("Far Rockaway".equals(branching.targetDestination)
        && "Ozone Park".equals(branching.followingDestination), "keep branch destinations");
    Decision routes = Decision.evaluate(now, now, "ok", Arrays.asList(
        new Decision.Train("train-n", now + 780, "Astoria", "N", ""),
        new Decision.Train("train-w", now + 1260, "Bay Ridge", "W", "")), 10, 1);
    check("N".equals(routes.targetRoute) && "W".equals(routes.followingRoute),
        "keep actual line identity for both opportunities");
    Decision priorLine = Decision.evaluate(now, now, "ok",
        Collections.singletonList(new Decision.Train("same-id", now + 900, "Astoria", "N", "")),
        10, 0);
    Decision changedLine = Decision.evaluate(now, now, "ok",
        Collections.singletonList(new Decision.Train("same-id", now + 780, "Bay Ridge", "W", "")),
        10, 0);
    check(ChimePolicy.crossing(priorLine, changedLine) == 0,
        "route identity change cannot reuse another line's train chime identity");
    Decision tied = Decision.evaluate(now, now, "ok", Arrays.asList(
        new Decision.Train("b", now + 630, "Ozone Park"),
        new Decision.Train("a", now + 630, "Far Rockaway")), 10, 0);
    check("a".equals(tied.targetId) && "b".equals(tied.followingId)
        && tied.targetTime == tied.followingTime && tied.alternateLeaveSeconds == 0,
        "tie-break by ID and preserve the same zero-buffer leave boundary");
    Decision unknown = Decision.evaluate(now, now, "ok", Arrays.asList(
        new Decision.Train(null, now + 630, ""),
        new Decision.Train("b", now + 780, "")), 10, 1);
    check(unknown.targetTime == now + 630 && unknown.leaveNow && unknown.followingTime == 0,
        "blank primary identity cannot establish a distinct following train");

    d =
        Decision.evaluate(
            now,
            now,
            "ok",
            Collections.singletonList(new Decision.Train("buffer-edge", now + 11 * 60L, "Uptown")),
            10,
            1);
    check(d.leaveNow && "Leave now".equals(d.headline), "buffer edge is a leave-now cue");

    d =
        Decision.evaluate(
            now,
            now,
            "ok",
            Collections.singletonList(
                new Decision.Train("buffer-wait", now + 11 * 60L + 30, "South Ferry")),
            10,
            1);
    check(
        !d.leaveNow && "Leave in <1 min".equals(d.headline),
        "remain waiting before positive-buffer boundary");

    d =
        Decision.evaluate(
            now,
            now,
            "ok",
            Collections.singletonList(new Decision.Train("zero-buffer-edge", now + 60, "Uptown")),
            0,
            0);
    check(
        !d.leaveNow && "Leave in 1 min".equals(d.headline),
        "zero-buffer cue starts below 60 seconds");

    d =
        Decision.evaluate(
            now,
            now,
            "ok",
            Collections.singletonList(new Decision.Train("walk-zero", now + 30, "Downtown")),
            0,
            0);
    check(d.leaveNow && "walk-zero".equals(d.targetId), "zero walk and positive sub-minute slack");
    check("Leave now".equals(d.headline), "leave-now stays actionable for the last minute");

    d =
        Decision.evaluate(
            now,
            now,
            "ok",
            Collections.singletonList(new Decision.Train("catch", now + 5 * 60L, "Downtown")),
            4,
            2);
    check(d.leaveNow && "catch".equals(d.targetId), "allow leave-now inside buffer interval");

    d =
        Decision.evaluate(
            now,
            now,
            "ok",
            Collections.singletonList(new Decision.Train("missed", now + 30, "Downtown")),
            2,
            0);
    check(
        d.targetId == null && !d.leaveNow && "No reachable train".equals(d.headline),
        "never recommend unreachable train");
    check(d.next.startsWith("1 min · Downtown"), "retain unreachable arrival in predictions");

    d =
        Decision.evaluate(
            now,
            now,
            "ok",
            Arrays.asList(
                new Decision.Train("past", now, "Past"),
                new Decision.Train(" ", now - 30, "Past"),
                new Decision.Train("future", now - 1, "Past too")),
            0,
            0);
    check(
        "No upcoming trains".equals(d.headline) && d.next.length() == 0 && d.targetId == null,
        "ignore departed trains and invalid IDs");

    d =
        Decision.evaluate(
            now,
            now,
            "ok",
            Collections.singletonList(new Decision.Train("", now + 10 * 60L, "South Ferry")),
            10,
            0);
    check(
        d.leaveNow && d.targetId == null && d.next.contains("10 min"),
        "anonymous arrivals remain visible without a chime ID");
    for (String status : new String[] {"stale", "unavailable", "ok"}) {
      long feed = "ok".equals(status) ? now - 61 : now;
      d =
          Decision.evaluate(
              now,
              feed,
              status,
              Collections.singletonList(new Decision.Train("hidden", now + 300, "Uptown")),
              2,
              0);
      check(
          !d.live && d.targetId == null && d.next.length() == 0,
          "suppress stale/unavailable countdowns");
    }
    d = Decision.evaluate(now, now, "ok", Collections.<Decision.Train>emptyList(), 0, 0);
    check(
        d.live && d.targetId == null && "No upcoming trains".equals(d.headline),
        "empty live result");

    d = Decision.evaluate(now, now, "ok", java.util.Arrays.asList(
        new Decision.Train("late", now + 780, "Far Rockaway"),
        new Decision.Train("late", now + 800, "Far Rockaway"),
        new Decision.Train("next", now + 1260, "Ozone Park")), 10, 1);
    check("late".equals(d.targetId) && "next".equals(d.followingId), "distinct following identity");
    check(d.targetTime == now + 780 && d.followingTime == now + 1260, "structured opportunity times");
    check("Ozone Park".equals(d.followingDestination) && d.alternateLeaveSeconds == 600, "secondary destination and leave time");

    for (int margin : new int[] {0, 1}) {
      d =
          Decision.evaluate(
              now,
              now,
              "ok",
              Collections.singletonList(
                  new Decision.Train("deadline", now + 600 + margin * 60 + 61, "Downtown")),
              10,
              margin);
      check("Leave in 1 min".equals(d.headline), "never round walking deadline up");
    }
    int weekdays = 0x1f;
    String key = Decision.chimeWindow(ny(2026, 11, 23, 23, 0), weekdays, 22 * 60, 2 * 60);
    check(
        key != null
            && key.equals(Decision.chimeWindow(ny(2026, 11, 24, 1, 59), weekdays, 22 * 60, 2 * 60)),
        "overnight window remains keyed to start date");
    check(
        Decision.chimeWindow(ny(2026, 11, 24, 2, 0), weekdays, 22 * 60, 2 * 60) == null,
        "window end is exclusive");
    check(
        Decision.chimeWindow(ny(2026, 11, 21, 23, 0), weekdays, 22 * 60, 2 * 60) == null,
        "weekday mask uses window start day");
    check(
        Decision.chimeWindow(ny(2026, 11, 24, 12, 0), 1 << 1, 11 * 60, 13 * 60) != null,
        "Monday is bit zero and Tuesday bit one");
    check(
        Decision.chimeWindow(ny(2026, 11, 24, 12, 0), 1, 13 * 60, 11 * 60) == null,
        "outside window");
    System.out.println("DecisionTest passed");
  }
}
