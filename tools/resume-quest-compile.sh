#!/usr/bin/env bash
set -Eeuo pipefail
trap 'printf "\nThe build stopped. Please send a photo of the error.\n" >&2' ERR
TASK_ROOT="$HOME/aster-quest-build"
PROJECT_DIR="$TASK_ROOT/aster-browser-questfixed"
SOURCE_DIR="$TASK_ROOT/chromium/src"
if [ ! -d "$PROJECT_DIR/.git" ] || [ ! -d "$SOURCE_DIR/.git" ]; then
    printf '\nThe previous project or Chromium checkout was not found. Please send a photo.\n' >&2
    exit 1
fi
# Serialize this helper without removing the Chromium build tool's own lock.
exec 9>"$TASK_ROOT/.aster-compile-resume.lock"
if ! flock -n 9; then
    printf '\nAnother resume command is already running. Please return to that window.\n' >&2
    exit 2
fi
if pgrep -u "$(id -u)" -f '[/]siso/cipd/siso|[a]utoninja|[n]inja -C' >/dev/null; then
    printf '\nAnother build is still running. Do not start another one; please send a photo of that build window.\n' >&2
    exit 2
fi
printf '\nFetching the compatibility fix into the project checkout...\n'
git -C "$PROJECT_DIR" pull --ff-only
PATCH="$PROJECT_DIR/chromium-engine/afterbird-source/patches/m151/0039-aster-chromium151-build-compat.patch"
if git -C "$SOURCE_DIR" apply --check "$PATCH"; then
    git -C "$SOURCE_DIR" apply "$PATCH"
elif git -C "$SOURCE_DIR" apply --reverse --check "$PATCH"; then
    printf 'The compatibility fix is already applied.\n'
else
    printf '\nThe compatibility fix did not match the existing checkout. Please send a photo.\n' >&2
    exit 1
fi
export PATH="$TASK_ROOT/tools/gradle-9.6.0/bin:$TASK_ROOT/tools/depot_tools:$PATH"
cd "$SOURCE_DIR"
printf '\nResuming compilation using existing build outputs. Keep this SSH window open.\n'
autoninja -C out/afterbird_production -j 8 chrome_public_apk
APK="$SOURCE_DIR/out/afterbird_production/apks/ChromePublic.apk"
if [ ! -f "$APK" ]; then
    printf '\nThe expected APK was not found: %s\n' "$APK" >&2
    exit 1
fi
printf '\nBUILD FINISHED. APK: %s\n' "$APK"
sha256sum "$APK"
