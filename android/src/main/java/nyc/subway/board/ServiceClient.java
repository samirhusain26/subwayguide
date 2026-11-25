package nyc.subway.board;

import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import org.json.JSONObject;

/** Small read-only LAN client. No device identifiers or credentials are sent. */
final class ServiceClient {
  static String origin(String input) {
    String value = input.trim();
    if (value.length() == 0) return "";
    if (!value.contains("://")) value = "http://" + value;
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme();
      if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
          || uri.getPort() > 65535
          || uri.getPort() == 0) throw new IllegalArgumentException();
      int port = uri.getPort();
      if (port < 0) port = "http".equalsIgnoreCase(scheme) ? 8765 : 443;
      return scheme + "://" + uri.getHost() + ":" + port;
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Enter a host or IP address and port, for example subwayboard:8765.");
    }
  }

  /** One sequential board poll can have at most one HTTP connection in flight. */
  static final class Poll {
    private boolean cancelled;
    private HttpURLConnection connection;

    synchronized void attach(HttpURLConnection next) throws IOException {
      if (cancelled) throw new java.io.InterruptedIOException("Poll superseded");
      connection = next;
    }

    synchronized void detach() { connection = null; }

    synchronized void cancel() {
      cancelled = true;
      if (connection != null) connection.disconnect();
    }
  }

  static JSONObject board(JSONObject config, String route, String direction, Poll poll) throws Exception {
    String path =
        new Uri.Builder()
            .path("/v1/board")
            .appendQueryParameter("station", config.getString("station"))
            .appendQueryParameter("route", route)
            .appendQueryParameter("direction", direction)
            .build()
            .toString();
    return get(origin(config.getString("server")) + path, poll);
  }

  static final class UnknownSelection extends IOException {
    UnknownSelection() {
      super("The selected stop or route is no longer available.");
    }
  }

  static JSONObject get(String address) throws Exception {
    return get(address, null);
  }

  private static JSONObject get(String address, Poll poll) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
    c.setConnectTimeout(4000);
    c.setReadTimeout(10000);
    c.setInstanceFollowRedirects(false);
    c.setUseCaches(false);
    c.setRequestProperty("Accept", "application/json");
    try {
      if (poll != null) poll.attach(c);
      int code = c.getResponseCode();
      if (code == 400) throw new UnknownSelection();
      if (code != 200) throw new IOException("Service returned " + code);
      try (InputStream input = c.getInputStream()) {
        return new JSONObject(read(input));
      }
    } finally {
      if (poll != null) poll.detach();
      c.disconnect();
    }
  }

  static String read(InputStream input) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int count;
    while ((count = input.read(chunk)) != -1) {
      if (bytes.size() + count > 2_000_000) throw new IOException("Response too large");
      bytes.write(chunk, 0, count);
    }
    return bytes.toString("UTF-8");
  }
}
