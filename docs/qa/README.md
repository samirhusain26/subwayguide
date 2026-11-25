# Landscape board previews

The 1.2.1 native board is shown in the [nearby stops](nyc-nearby-stops.png) and [full route](nyc-full-route.png) screenshots. Both use the same sample arrivals for the downtown 1 at 125 St. The earlier dashboard examples follow.

These 1280 × 720 PNGs were rendered on the Mac from the production Android views using Robolectric 4.16.1 native graphics and Android API 35. The viewport represents a 640 × 360 dp landscape screen at 320 dpi. All train times, service notices and weather in the images are test fixtures.

| Preview | State |
| --- | --- |
| [Walking decision](board-rain.png) | A ten-minute walk to the platform. After skipping the four-minute train, the rider can leave in three minutes for the thirteen-minute train. The umbrella shows a rain forecast. |
| [Leave now](board-leave-now.png) | The target train reaches the ten-minute walking threshold. |
| [Express service](board-express.png) | The F express uses an orange diamond, and trains to different destinations have separate rows. |
| [Delayed feed](board-delayed.png) | The feed is over 60 seconds old. The board displays the last update time and clears the predictions. |
| [Service change](board-service-change.png) | A synthetic platform suspension. The board explains the change and displays the matching notice. |
| [Trip settings](settings-trip.png) | The selected line and direction at 125 St. The right pane scrolls to the walking time and boarding buffer. |
| [Sound settings](settings-sound.png) | Chime enabled for weekday mornings, once per commute window. |
| [Connection](settings-connection.png) | On-phone data is selected by default. A home server is optional. |
| [Larger text](settings-large-type.png) | Connection at 130% system font scale. |

## Reproduce

Use a JDK 21 or later, Maven 3.9, unzip and Android SDK platform 35 or 36. Set `JAVA_HOME` and `ANDROID_HOME`. Set `MAVEN` if the executable is outside PATH. The first run retrieves the test dependencies and instrumented Android framework. `SUBWAY_QA_CACHE` can point at dedicated external build and cache storage. On the owner's Mac, run from the project directory through the storage guard.

```sh
~/.local/bin/dev-storage run -- docs/qa/host/run.sh
```

On other machines run `docs/qa/host/run.sh`. An optional first argument selects another output directory for the PNGs. The harness compiles the app source, exercises native view listeners and lays out the screens at the dimensions above. Its activity test serves synthetic responses on an ephemeral loopback port.

To verify a freshly built APK, use the CPython 3.11 interpreter used to build it and run `python3.11 -I -S docs/qa/host/check_package.py` through the storage guard on the development Mac. The check compares the packaged station directory and five application modules with the checkout, inspects both ARM libraries, and exercises the shipped Python bytecode and dependencies on the host. Add `--live` to request MTA and NWS updates for 125 St. Android system libraries and the JNI startup path require an Android device.

## Version 1.2.1 verification

The eight API 35 host tests passed, including both audible-tone requests, duplicate suppression, the manual sound check, express and branch selection, and the map fade behind the countdown. The two pure Java suites and 61 Python service tests also passed. The release build and package checks verified the embedded service, both ARM runtimes, the catalog and every packaged map asset. The API 22 signing certificate matches the earlier APK. Physical Moto speaker volume and screen appearance still require a handset check.

The tested release APK has SHA-256 `7e62af9e4753b074fd486c0e3b4cd819d11404babda1cd032c5548f8d24dfb24`.

## Earlier dashboard verification

Five host tests passed on November 25. A fresh activity used the on-phone source automatically for 116/1/S. The test substitutes a fixture for the Android Python bridge, switches to a real loopback HTTP fixture, and switches back. It exercises malformed and empty responses, stale data, platform service changes, and chime deduplication, including missing or mistyped train identities. A distinct test rejects a selected route that disappears from a replacement directory.

The settings test searched the bundled directory for Columbus Circle, selected the C train uptown, edited walking and boarding times, changed commute days and the ending time, saved, and reopened the selection. Scroll checks confirmed that the full 48 dp walking and buffer controls, the sound window’s ending time and the fixed Save button can be reached. The larger-type check verifies that the Connection tab fits on one line.

At 1280 × 720 the visible “3” in the wait cue is 260 pixels tall. The “NOW” glyphs are 224 pixels tall. Those measurements describe the rendered images. A viewing-distance check depends on the actual panel, dock placement and room lighting.

## Device coverage

The device panel reported “The emulator process for ‘SubwayBoard_Embedded_QA_20261124’ exited with code 1 before coming online.” The dedicated Android 16 emulator started on a separate port during the retry, but the panel again selected the occupied port. The PNGs are host renders. The target handset still needs an install and cold-start check, a live Wi-Fi test, and checks for readability at seven to ten feet, sound and overnight operation.
