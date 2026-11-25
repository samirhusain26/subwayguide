package nyc.subway.board;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import java.io.File;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class UpdateInstallerTest {
  private static final String PACKAGE = "nyc.subway.board";
  private Application app;
  private ShadowPackageManager packages;
  private File apk;

  private static PackageInfo info(String name, int code, byte signer) {
    PackageInfo record = new PackageInfo();
    record.packageName = name;
    if (android.os.Build.VERSION.SDK_INT < 28) {
      record.versionCode = code;
      record.signatures = new Signature[]{new Signature(new byte[]{signer})};
      return record;
    }
    record.setLongVersionCode(code);
    record.signingInfo = ReflectionHelpers.callConstructor(SigningInfo.class);
    Shadows.shadowOf(record.signingInfo).setSignatures(new Signature[]{new Signature(new byte[]{signer})});
    return record;
  }

  @Before public void setup() throws Exception {
    app = RuntimeEnvironment.getApplication();
    packages = Shadows.shadowOf(app.getPackageManager());
    packages.installPackage(info(PACKAGE, 6, (byte) 1));
    apk = new File(app.getFilesDir(), "fixture.apk");
    Files.write(apk.toPath(), new byte[]{1, 2, 3});
  }

  @Test public void rejectsWrongAppSignerAndVersion() {
    packages.setPackageArchiveInfo(apk.getPath(), info("example.other", 7, (byte) 1));
    assertThrows(IllegalArgumentException.class, () -> UpdateInstaller.validate(app, apk));
    packages.setPackageArchiveInfo(apk.getPath(), info(PACKAGE, 7, (byte) 2));
    assertThrows(SecurityException.class, () -> UpdateInstaller.validate(app, apk));
    for (int old : new int[]{5, 6}) {
      packages.setPackageArchiveInfo(apk.getPath(), info(PACKAGE, old, (byte) 1));
      assertThrows(IllegalArgumentException.class, () -> UpdateInstaller.validate(app, apk));
    }
  }

  @Test @Config(sdk = 27) public void legacySignerAndVersionAreChecked() throws Exception {
    rejectsWrongAppSignerAndVersion();
    packages.setPackageArchiveInfo(apk.getPath(), info(PACKAGE, 7, (byte) 1));
    assertEquals(7, UpdateInstaller.code(UpdateInstaller.validate(app, apk)));
  }

  @Test public void commitsNewerSignedPackageAndOpensSystemConfirmation() throws Exception {
    packages.setPackageArchiveInfo(apk.getPath(), info(PACKAGE, 7, (byte) 1));
    UpdateInstaller.install(app, apk, apk.length());
    Intent status = Shadows.shadowOf(app).getNextStartedActivity();
    assertNotNull("Session commit delivers the app's explicit status callback", status);
    assertEquals(UpdateInstallActivity.class.getName(), status.getComponent().getClassName());
    // Supply the user-confirmation result which the real system sends before committing.
    Intent confirmation = new Intent("android.content.pm.action.CONFIRM_INSTALL");
    status.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
        .putExtra(Intent.EXTRA_INTENT, confirmation);
    UpdateInstallActivity callback = Robolectric.buildActivity(UpdateInstallActivity.class, status).create().get();
    assertEquals(confirmation.getAction(), Shadows.shadowOf(callback).getNextStartedActivity().getAction());
    assertTrue(callback.isFinishing());
    int id = status.getIntExtra(UpdateInstaller.CALLBACK_SESSION, -1);
    assertTrue(id >= 0);
    assertEquals(id, app.getSharedPreferences("subway-update", 0).getInt("activeSession", -1));
    // The shadow does not model commit() sealing the SessionInfo; supply Android's sealed state to
    // verify startup recovery preserves a submitted session after the process-local guard is lost.
    PackageInstaller.SessionInfo sessionInfo = app.getPackageManager().getPackageInstaller().getSessionInfo(id);
    ReflectionHelpers.setField(sessionInfo, "sealed", true);
    MainActivity.UPDATE_INSTALLING.set(false);
    assertTrue(UpdateInstaller.active(app));
    UpdateInstaller.finished(app, id + 1);
    assertEquals(id, app.getSharedPreferences("subway-update", 0).getInt("activeSession", -1));
    status.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED);
    Robolectric.buildActivity(UpdateInstallActivity.class, status).create();
    assertFalse(UpdateInstaller.active(app));
  }

  @Test public void promotesIncomingAndKeepsNewerValidCache() throws Exception {
    File incoming = new File(app.getFilesDir(), "incoming.apk");
    File cached = new File(app.getFilesDir(), "SubwayBoard.apk");
    Files.write(incoming.toPath(), new byte[]{7});
    Files.write(cached.toPath(), new byte[]{6});
    packages.setPackageArchiveInfo(incoming.getPath(), info(PACKAGE, 7, (byte) 1));
    packages.setPackageArchiveInfo(cached.getPath(), info(PACKAGE, 8, (byte) 1));

    assertEquals(8, UpdateInstaller.code(UpdateInstaller.promote(app, incoming, cached)));
    assertArrayEquals(new byte[]{6}, Files.readAllBytes(cached.toPath()));
    assertTrue(incoming.exists());

    packages.setPackageArchiveInfo(incoming.getPath(), info(PACKAGE, 9, (byte) 1));
    assertEquals(9, UpdateInstaller.code(UpdateInstaller.promote(app, incoming, cached)));
    assertArrayEquals(new byte[]{7}, Files.readAllBytes(cached.toPath()));
    assertFalse(incoming.exists());
  }

  @Test public void discardsAnUnsubmittedSessionLeftByProcessDeath() throws Exception {
    discardUnsubmittedSession();
  }

  @Test @Config(sdk = 27)
  public void discardsAnUnsubmittedSessionUsingTheApi26SealedState() throws Exception {
    discardUnsubmittedSession();
  }

  private void discardUnsubmittedSession() throws Exception {
    PackageInstaller installer = app.getPackageManager().getPackageInstaller();
    int id = installer.createSession(new PackageInstaller.SessionParams(
        PackageInstaller.SessionParams.MODE_FULL_INSTALL));
    app.getSharedPreferences("subway-update", 0).edit().putInt("activeSession", id).commit();

    assertFalse(UpdateInstaller.active(app));
    assertNull("An unsubmitted session is abandoned during startup recovery", installer.getSessionInfo(id));
    assertFalse(app.getSharedPreferences("subway-update", 0).contains("activeSession"));
  }
}
