package nyc.subway.board;

import android.app.PendingIntent;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** Validates an update against this installation and hands it to Android's installer. */
final class UpdateInstaller {
  private static final String PACKAGE = "nyc.subway.board";

  private static final String SESSION = "activeSession";
  static final String CALLBACK_SESSION = "nyc.subway.board.UPDATE_SESSION";

  private UpdateInstaller() {}

  private static SharedPreferences state(Context context) {
    return context.getSharedPreferences("subway-update", Context.MODE_PRIVATE);
  }

  static boolean active(Context context) {
    if (MainActivity.UPDATE_INSTALLING.get()) return true;
    SharedPreferences state = state(context);
    int id = state.getInt(SESSION, -1);
    if (id == -1) return false;
    PackageInstaller installer = context.getPackageManager().getPackageInstaller();
    PackageInstaller.SessionInfo info = installer.getSessionInfo(id);
    if (info == null) {
      finished(context, id);
      return false;
    }
    if (Build.VERSION.SDK_INT >= 26) {
      if (info.isSealed() || (Build.VERSION.SDK_INT >= 29 && info.isCommitted())) return true;
      abandon(context, id);
      return false;
    }
    // On older Android, an open staged file means the session was never submitted.
    // A committed session refuses openRead with SecurityException.
    try (PackageInstaller.Session session = installer.openSession(id)) {
      try (java.io.InputStream ignored = session.openRead("update.apk")) {
        abandon(context, id);
        return false;
      } catch (SecurityException submitted) {
        return true;
      } catch (java.io.FileNotFoundException incomplete) {
        abandon(context, id);
        return false;
      } catch (java.io.IOException incomplete) {
        // An unreadable session may still be submitted; retain it to avoid a duplicate install.
        return true;
      }
    } catch (SecurityException submitted) {
      return true;
    } catch (java.io.IOException unavailable) {
      return true;
    } catch (RuntimeException unavailable) {
      finished(context, id);
      return false;
    }
  }

  static synchronized void finished(Context context, int id) {
    SharedPreferences state = state(context);
    if (id >= 0 && state.getInt(SESSION, -1) == id) state.edit().remove(SESSION).commit();
  }

  static void abandon(Context context, int id) {
    try {
      if (id >= 0) context.getPackageManager().getPackageInstaller().abandonSession(id);
    } catch (RuntimeException alreadyClosed) {
      // A completed system session has nothing left to abandon.
    } finally {
      finished(context, id);
    }
  }

  static String label(PackageInfo candidate) {
    return candidate.versionName == null || candidate.versionName.trim().isEmpty()
        ? "build " + code(candidate) : candidate.versionName;
  }

  static long code(PackageInfo info) {
    return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
  }

  static PackageInfo validate(Context context, File apk) throws Exception {
    PackageManager manager = context.getPackageManager();
    PackageInfo installed = manager.getPackageInfo(PACKAGE, Build.VERSION.SDK_INT >= 28
        ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
    PackageInfo candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(),
        Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
    if (candidate == null || !PACKAGE.equals(candidate.packageName))
      throw new IllegalArgumentException("This file is not a Subway Board update.");
    if (code(candidate) <= code(installed))
      throw new IllegalArgumentException("No newer update is available.");
    if (!sameSigner(installed, candidate))
      throw new SecurityException("The update signing certificate does not match this installation.");
    return candidate;
  }

  /** Validates an incoming APK and atomically keeps the newest valid cached update. */
  static PackageInfo promote(Context context, File incoming, File cached) throws Exception {
    PackageInfo candidate = validate(context, incoming);
    PackageInfo saved = null;
    if (cached.isFile()) {
      try {
        saved = validate(context, cached);
      } catch (IllegalArgumentException | SecurityException invalidCached) {
        // A stale or untrusted cache must not block a valid download.
      }
    }
    if (saved != null && code(candidate) < code(saved)) return saved;
    if (Build.VERSION.SDK_INT >= 26) {
      try {
        java.nio.file.Files.move(incoming.toPath(), cached.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
        android.system.Os.rename(incoming.getAbsolutePath(), cached.getAbsolutePath());
      }
    } else {
      android.system.Os.rename(incoming.getAbsolutePath(), cached.getAbsolutePath());
    }
    return candidate;
  }

  private static boolean sameSigner(PackageInfo installed, PackageInfo candidate) throws Exception {
    Set<String> current = signerDigests(installed);
    Set<String> proposed = signerDigests(candidate);
    return !current.isEmpty() && current.equals(proposed);
  }

  private static Set<String> signerDigests(PackageInfo info) throws Exception {
    Signature[] signatures;
    if (Build.VERSION.SDK_INT >= 28) {
      SigningInfo signing = info.signingInfo;
      if (signing == null) return java.util.Collections.emptySet();
      signatures = signing.getApkContentsSigners();
    } else {
      signatures = info.signatures;
    }
    Set<String> result = new HashSet<>();
    if (signatures == null) return result;
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (Signature signature : signatures) {
      byte[] value = digest.digest(signature.toByteArray());
      StringBuilder hex = new StringBuilder();
      for (byte b : value) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
      result.add(hex.toString());
    }
    return result;
  }

  static void install(Context context, File apk, long length) throws Exception {
    validate(context, apk);
    PackageInstaller installer = context.getPackageManager().getPackageInstaller();
    PackageInstaller.SessionParams params =
        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    params.setAppPackageName(PACKAGE);
    if (Build.VERSION.SDK_INT >= 31)
      params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
    int sessionId = installer.createSession(params);
    try {
      // Persist immediately so startup can reconcile the session if copying is interrupted.
      if (!state(context).edit().putInt(SESSION, sessionId).commit())
        throw new java.io.IOException("Could not save the pending installation.");
      try (PackageInstaller.Session session = installer.openSession(sessionId);
           FileInputStream input = new FileInputStream(apk)) {
        try (OutputStream output = session.openWrite("update.apk", 0, length)) {
          byte[] buffer = new byte[128 * 1024];
          int count;
          while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
          session.fsync(output);
        }
        Intent callback = new Intent(context, UpdateInstallActivity.class);
        callback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        callback.putExtra(CALLBACK_SESSION, sessionId);
        callback.setAction(context.getPackageName() + ".UPDATE_STATUS." + sessionId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent status;
        if (Build.VERSION.SDK_INT >= 35) {
          ActivityOptions options = ActivityOptions.makeBasic();
          options.setPendingIntentCreatorBackgroundActivityStartMode(
              ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
          status = PendingIntent.getActivity(context, sessionId, callback, flags, options.toBundle());
        } else {
          status = PendingIntent.getActivity(context, sessionId, callback, flags);
        }
        session.commit(status.getIntentSender());
      }
    } catch (Exception failure) {
      abandon(context, sessionId);
      throw failure;
    }
  }
}
