package nyc.subway.board;

import android.content.Context;
import android.os.Looper;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.io.InputStream;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/** App-local board service. All calls may perform bounded network I/O and must run off the UI thread. */
public final class LocalBoard {
  private static final Object LOCK = new Object();
  private static String bundledCatalog;
  private static boolean initialized;

  private LocalBoard() {}

  public static JSONObject board(Context context, String station, String route, String direction)
      throws Exception {
    requireWorkerThread();
    JSONObject result = new JSONObject(call(context, "board_json", station, route, direction).toString());
    if (result.has("__httpStatus")) {
      if (result.optInt("__httpStatus") == 400) throw new ServiceClient.UnknownSelection();
      throw new java.io.IOException("Local board service returned " + result.optInt("__httpStatus"));
    }
    return result;
  }

  public static JSONArray stations(Context context) throws Exception {
    requireWorkerThread();
    JSONObject directory = new JSONObject(call(context, "stations_json").toString());
    return directory.getJSONArray("stations");
  }

  /** Downloads an update without importing or initializing embedded_board. */
  public static long downloadUpdate(Context context, File destination) throws Exception {
    requireWorkerThread();
    if (context == null) throw new IllegalArgumentException("Context is required");
    PyObject downloader;
    synchronized (LOCK) {
      if (!Python.isStarted()) Python.start(new AndroidPlatform(context.getApplicationContext()));
      downloader = Python.getInstance().getModule("update_downloader");
    }
    return downloader.callAttr("download", destination.getAbsolutePath()).toLong();
  }

  private static PyObject call(Context context, String method, Object... args) {
    if (context == null) throw new IllegalArgumentException("Context is required");
    synchronized (LOCK) {
      try {
        if (bundledCatalog == null) {
          try (InputStream input = context.getAssets().open("stations.json")) {
            bundledCatalog = ServiceClient.read(input);
          }
        }
        if (!Python.isStarted()) Python.start(new AndroidPlatform(context.getApplicationContext()));
        PyObject module = Python.getInstance().getModule("embedded_board");
        if (!initialized) {
          module.callAttr("initialize", bundledCatalog, context.getFilesDir().getAbsolutePath());
          initialized = true;
        }
        return module.callAttr(method, args);
      } catch (Exception e) {
        throw new IllegalStateException("Local board service could not initialize", e);
      }
    }
  }

  private static void requireWorkerThread() {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      throw new IllegalStateException("LocalBoard must be called from a background thread");
    }
  }
}
