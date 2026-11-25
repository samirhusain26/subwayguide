package nyc.subway.board;

import android.app.PendingIntent;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
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

  private UpdateInstaller() {}

  static PackageInfo validate(Context context, File apk) throws Exception {
    PackageManager manager = context.getPackageManager();
    PackageInfo installed = manager.getPackageInfo(PACKAGE, Build.VERSION.SDK_INT >= 28
        ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
    PackageInfo candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(),
        Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
    if (candidate == null || !PACKAGE.equals(candidate.packageName))
      throw new IllegalArgumentException("This file is not a Subway Board update.");
    long currentCode = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
    long candidateCode = Build.VERSION.SDK_INT >= 28 ? candidate.getLongVersionCode() : candidate.versionCode;
    if (candidateCode <= currentCode)
      throw new IllegalArgumentException("No newer update is available.");
    if (!sameSigner(installed, candidate))
      throw new SecurityException("The update signing certificate does not match this installation.");
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
    } catch (Exception failure) {
      installer.abandonSession(sessionId);
      throw failure;
    }
  }
}
