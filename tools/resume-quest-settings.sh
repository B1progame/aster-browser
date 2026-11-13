#!/usr/bin/env bash
set -Eeuo pipefail
trap 'printf "\nThe build stopped. Please send a photo of the error.\n" >&2' ERR
TASK_ROOT="$HOME/aster-quest-build"
PROJECT_DIR="$TASK_ROOT/aster-browser-questfixed"
SOURCE_DIR="$TASK_ROOT/chromium/src"
APK="$SOURCE_DIR/out/afterbird_production/apks/ChromePublic.apk"
SIGNER="$TASK_ROOT/tools/android-sdk/build-tools/37.0.0/apksigner"
if [ ! -d "$PROJECT_DIR/.git" ] || [ ! -d "$SOURCE_DIR/.git" ] || [ ! -f "$APK" ]; then
    printf '\nThe previous checkout or APK was not found. Please send a photo.\n' >&2
    exit 1
fi
exec 9>"$TASK_ROOT/.aster-compile-resume.lock"
if ! flock -n 9; then
    printf '\nAnother resume command is already running. Please send a photo.\n' >&2
    exit 2
fi
if pgrep -u "$(id -u)" -f '[/]siso/cipd/siso|[a]utoninja|[n]inja -C' >/dev/null; then
    printf '\nAnother build is still running. Please let it finish first.\n' >&2
    exit 2
fi
if [ ! -x "$SIGNER" ]; then
    printf '\nAndroid apksigner was not found at %s. Please send a photo.\n' "$SIGNER" >&2
    exit 1
fi
OLD_CERT=$("$SIGNER" verify --print-certs "$APK" | awk '/certificate SHA-256 digest:/ { print $NF; exit }')
if [ -z "$OLD_CERT" ]; then
    printf '\nCould not read the previous APK signing certificate. Please send a photo.\n' >&2
    exit 1
fi
printf '\nFetching the settings fix...\n'
git -C "$PROJECT_DIR" pull --ff-only
PATCH="$PROJECT_DIR/chromium-engine/afterbird-source/patches/m151/0040-aster-settings-engine-handoff.patch"
if git -C "$SOURCE_DIR" apply --check "$PATCH" 2>/dev/null; then
    git -C "$SOURCE_DIR" apply "$PATCH"
elif git -C "$SOURCE_DIR" apply --reverse --check "$PATCH" 2>/dev/null; then
    printf 'The settings fix is already applied.\n'
else
    printf '\nThe settings fix does not match the existing checkout. Please send a photo.\n' >&2
    exit 1
fi
export PATH="$TASK_ROOT/tools/gradle-9.6.0/bin:$TASK_ROOT/tools/depot_tools:$PATH"
cd "$SOURCE_DIR"
printf '\nReusing existing build outputs. Keep this SSH window open.\n'
autoninja -C out/afterbird_production -j 8 chrome_public_apk
NEW_CERT=$("$SIGNER" verify --print-certs "$APK" | awk '/certificate SHA-256 digest:/ { print $NF; exit }')
if [ -z "$NEW_CERT" ]; then
    printf '\nCould not verify the new APK. Please send a photo.\n' >&2
    exit 1
fi
printf '\nBUILD FINISHED. APK: %s\n' "$APK"
sha256sum "$APK"
if [ "$OLD_CERT" = "$NEW_CERT" ]; then
    printf 'The new APK uses the same signing certificate as the previous build.\n'
else
    printf 'WARNING: The signing certificate changed. This APK cannot update the previous build directly.\n' >&2
fi
