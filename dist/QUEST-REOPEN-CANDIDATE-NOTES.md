# Quest 3S reopening — test candidate

An ARM64 combined Chromium/Gecko APK was built on 2026-11-12 and uploaded to the
fork's draft release. Its APK signature, package, ABI, native libraries, and
SHA-256 were independently checked. It has not been tested on Quest hardware;
the reported reopening hang is not confirmed fixed.

The candidate is signed with a different key from upstream v5.0.0. It cannot
directly update that installation; preserve its data before considering any
uninstall. The package metadata still says 5.0.0/805000000, so this is a manually
installed test candidate rather than an automatic update.

Source: `d808c20d0fa520857b3a02b0b3218e634ee9c9fe`
APK: `ChromePublic.apk` (ARM64)
SHA-256: `20390d298cf7fedefc01693d260dcb47d87b5a14d7b81bad1265ffbaecd9023d`

Changes address shared startup diagnostics, Gecko state persistence and hidden
session behavior, Android API compatibility, and Chromium patch/build errors.
See `docs/QUEST_REOPEN_REVIEW.md` for validation, open issues and the device test.
