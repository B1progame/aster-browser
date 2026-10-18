# Browser updates

Aster uses one public GitHub release stream for both engines. Each release tag is a three-part version such as `v2.1.0`. The two apps keep separate Android package IDs and must be built and signed separately:

| Engine | Android package | Release APK name |
| --- | --- | --- |
| Chromium | `app.aster.chromium` | `AsterBrowser-vX.Y.Z-Android-arm64.apk` or `AsterBrowser-vX.Y.Z-Android-x86_64.apk` |
| GeckoView | `app.aster.browser` | `AsterBrowser-vX.Y.Z-Gecko-Android-arm64.apk` or `AsterBrowser-vX.Y.Z-Gecko-Android-x86_64.apk` |

The updater checks the latest stable GitHub release, compares its tag with the installed Aster version, and chooses the APK for the device ABI and engine. Downloading uses Android's DownloadManager; Android presents the APK installation confirmation. Each APK must retain the signing certificate of the installed copy or Android will refuse an in-place update.

The public `v2.1.0` release contains both engine APK pairs. Chromium and GeckoView are separate Android packages, so each updater selects the matching APK by engine and device ABI. The local GeckoView release build currently uses this workstation's Android debug signing key to remain compatible with the installed development build; it is not a Play Store signing setup, and future GeckoView releases must use the same key to update that installation.
