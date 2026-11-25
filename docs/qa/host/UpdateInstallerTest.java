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
  }
}
