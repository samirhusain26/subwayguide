package nyc.subway.board;

import java.util.Collections;

public final class ChimePolicyTest {
  private static Decision sample(String id, int seconds, String status, int buffer) {
    return Decision.evaluate(1000, 1000, status,
        Collections.singletonList(new Decision.Train(id, 1600 + buffer * 60 + seconds, "South Ferry")),
        10, buffer);
  }

  public static void main(String[] args) {
    for (int buffer : new int[] {0, 2}) {
      Decision before = sample("A", 121, "ok", buffer);
      Decision first = sample("A", 120, "ok", buffer);
      Decision later = sample("A", 31, "ok", buffer);
      Decision second = sample("A", 30, "ok", buffer);
      assert ChimePolicy.crossing(before, first) == 120;
      assert ChimePolicy.crossing(first, later) == 0;
      assert ChimePolicy.crossing(later, second) == 30;
      assert ChimePolicy.crossing(before, second) == 30;
      assert ChimePolicy.crossing(second, second) == 0;
      assert ChimePolicy.crossing(second, before) == 0;
      assert ChimePolicy.crossing(before, sample("A", 0, "ok", buffer)) == 0;
      assert ChimePolicy.crossing(null, first) == 0;
      assert ChimePolicy.crossing(before, sample("B", 119, "ok", buffer)) == 0;
      assert ChimePolicy.crossing(before, sample("", 119, "ok", buffer)) == 0;
      assert ChimePolicy.crossing(sample("A", 121, "stale", buffer), first) == 0;
      assert ChimePolicy.crossing(before, sample("A", 119, "stale", buffer)) == 0;
    }
    System.out.println("ChimePolicyTest passed");
  }
}
