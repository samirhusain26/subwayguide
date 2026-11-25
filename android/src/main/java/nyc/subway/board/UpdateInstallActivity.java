package nyc.subway.board;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.widget.Toast;

/** Foreground trampoline for PackageInstaller's pending user confirmation result. */
public final class UpdateInstallActivity extends Activity {
  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    Intent result = getIntent();
    int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
      Intent confirmation = result.getParcelableExtra(Intent.EXTRA_INTENT);
      if (confirmation != null) {
        try {
          startActivity(confirmation);
        } catch (RuntimeException failure) {
          MainActivity.UPDATE_INSTALLING.set(false);
          Toast.makeText(this, "Return to Update app and tap Install update again.", Toast.LENGTH_LONG).show();
        }
      } else {
        MainActivity.UPDATE_INSTALLING.set(false);
        Toast.makeText(this, "Android could not open the installer. Try Install update again.", Toast.LENGTH_LONG).show();
      }
    } else if (status == PackageInstaller.STATUS_SUCCESS) {
      MainActivity.UPDATE_INSTALLING.set(false);
      Toast.makeText(this, "Subway Board updated.", Toast.LENGTH_LONG).show();
    } else {
      MainActivity.UPDATE_INSTALLING.set(false);
      String detail = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
      String message = status == PackageInstaller.STATUS_FAILURE_BLOCKED
          ? "Android blocked this install. On Android 5.1, open Settings → Security → Unknown sources and enable it, then return to Update app and try again."
          : "Android could not install the update. " + (detail == null ? "Try again." : detail);
      Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
    finish();
  }
}
