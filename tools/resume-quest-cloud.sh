#!/usr/bin/env bash
set -Eeuo pipefail
trap 'printf "\nThe build stopped. Please send a photo of the error.\n" >&2' ERR
TASK_ROOT="$HOME/aster-quest-build"
PROJECT_DIR="$TASK_ROOT/aster-browser-questfixed"
if [ ! -d "$PROJECT_DIR/.git" ]; then
    printf 'The previous Aster checkout was not found. Please send a photo.\n' >&2
    exit 1
fi
printf '\nDownloading the corrected patches into the existing checkout...\n'
git -C "$PROJECT_DIR" pull --ff-only
export ANDROID_HOME="$TASK_ROOT/tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$TASK_ROOT/tools/gradle-9.6.0/bin:$TASK_ROOT/tools/depot_tools:$PATH"
export AFTERBIRD_ARGS_VARIANT=aster-arm64
export AFTERBIRD_CHROMIUM_WORKDIR="$TASK_ROOT/chromium"
cd "$PROJECT_DIR"
printf '\nContinuing the ARM64 build; the existing Chromium downloads will be reused.\n'
bash chromium-engine/afterbird-source/ci/chromium_android_pipeline.sh --full-build
APK="$TASK_ROOT/chromium/src/out/afterbird_production/apks/ChromePublic.apk"
if [ ! -f "$APK" ]; then
    printf '\nThe expected APK was not found: %s\n' "$APK" >&2
    exit 1
fi
printf '\nBUILD FINISHED. APK: %s\n' "$APK"
sha256sum "$APK"
