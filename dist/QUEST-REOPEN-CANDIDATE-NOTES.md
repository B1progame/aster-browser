# Quest 3S reopening — test candidate

An ARM64 combined Chromium/Gecko APK was built on 2026-11-12 and uploaded to the
fork's public `v5.1.0` test release. Its APK signature, package, ABI, native libraries, and
SHA-256 were independently checked. It has not been tested on Quest hardware;
the reported reopening hang is not confirmed fixed.

The candidate is signed with a different key from upstream v5.0.0. It cannot
directly update that installation; preserve its data before considering any
uninstall. The release tag is v5.1.0, but the APK package metadata still says 5.0.0/805000000.
This is a manually installed test candidate rather than an automatic update.

Source: `d808c20d0fa520857b3a02b0b3218e634ee9c9fe`
APK: `AsterBrowser-v5.1.0-Quest-arm64-test.apk`
SHA-256: `20390d298cf7fedefc01693d260dcb47d87b5a14d7b81bad1265ffbaecd9023d`

Changes address shared startup diagnostics, Gecko state persistence and hidden
session behavior, Android API compatibility, and Chromium patch/build errors.
See `docs/QUEST_REOPEN_REVIEW.md` for validation, open issues and the device test.
