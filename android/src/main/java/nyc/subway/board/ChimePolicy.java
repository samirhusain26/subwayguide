package nyc.subway.board;

/** Threshold crossings use unrounded time until the rider must leave. */
public final class ChimePolicy {
  private ChimePolicy() {}

  public static int crossing(Decision previous, Decision current) {
    if (previous == null || current == null || !previous.live || !current.live
        || current.targetId == null || !current.targetId.equals(previous.targetId)
        || current.leaveSeconds <= 0) return 0;
    // On an ETA revision across both thresholds, only the more urgent cue is relevant.
    for (int threshold : THRESHOLDS) {
      if (previous.leaveSeconds > threshold && current.leaveSeconds <= threshold) return threshold;
    }
    return 0;
  }

  private static final int[] THRESHOLDS = {30, 120};
}
