#!/usr/bin/env bash
set -Eeuo pipefail
trap 'printf "\nBuild stopped at line %s. Take a photo of the error and send it to Codex.\n" "$LINENO" >&2' ERR

# This helper sets up a dedicated Chromium workspace. The Chromium integration
# pipeline resets and cleans that dedicated checkout before applying its patches.
# It does not delete browser data on the Quest.
if [ "$(uname -m)" != x86_64 ]; then
    printf 'This build requires an x86-64 Linux VM. Your VM is %s.\n' "$(uname -m)" >&2
    exit 1
fi
available_gb=$(df -BG --output=avail "$HOME" | tail -1 | tr -dc '0-9')
if [ "${available_gb:-0}" -lt 100 ]; then
    printf 'At least 100 GB of free space is needed; only %s GB is available.\n' "${available_gb:-0}" >&2
    exit 1
fi

TASK_ROOT="$HOME/aster-quest-build"
TOOLS_DIR="$TASK_ROOT/tools"
CHROMIUM_DIR="$TASK_ROOT/chromium"
PROJECT_DIR="$TASK_ROOT/aster-browser-questfixed"
mkdir -p "$TOOLS_DIR" "$CHROMIUM_DIR"

printf '\nInstalling system build tools. A sudo password may be requested.\n'
sudo apt-get update
sudo apt-get install -y ca-certificates curl git openjdk-17-jdk python3 rsync unzip xz-utils zip

if [ ! -x "$TOOLS_DIR/gradle-9.6.0/bin/gradle" ]; then
    curl -fL --retry 3 https://services.gradle.org/distributions/gradle-9.6.0-bin.zip -o "$TOOLS_DIR/gradle.zip"
    unzip -q "$TOOLS_DIR/gradle.zip" -d "$TOOLS_DIR"
fi
export PATH="$TOOLS_DIR/gradle-9.6.0/bin:$PATH"
export ANDROID_HOME="$TOOLS_DIR/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ ! -x "$TOOLS_DIR/android-cmdline/cmdline-tools/bin/sdkmanager" ]; then
    mkdir -p "$TOOLS_DIR/android-cmdline"
    curl -fL --retry 3 https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip -o "$TOOLS_DIR/android-cmdline.zip"
    unzip -q "$TOOLS_DIR/android-cmdline.zip" -d "$TOOLS_DIR/android-cmdline"
fi
SDKMANAGER="$TOOLS_DIR/android-cmdline/cmdline-tools/bin/sdkmanager"
printf '\nAndroid SDK licenses follow. Review them and enter y if you accept each one.\n'
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" 'platform-tools' 'platforms;android-37.2' 'build-tools;37.0.0'

if [ ! -d "$TOOLS_DIR/depot_tools/.git" ]; then
    git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git "$TOOLS_DIR/depot_tools"
fi
export PATH="$TOOLS_DIR/depot_tools:$PATH"
if [ ! -d "$PROJECT_DIR/.git" ]; then
    git clone --branch main https://github.com/bprosusupro-rgb/aster-browser-questfixed.git "$PROJECT_DIR"
fi
printf '\nBuilding fork commit: '
git -C "$PROJECT_DIR" rev-parse HEAD

# Fetch only the pinned Chromium checkout first, so its official dependency
# installer can prepare the VM before the project's normal gclient sync.
if [ ! -d "$CHROMIUM_DIR/src/.git" ]; then
    git clone --no-checkout --depth 1 --branch 151.0.7922.38 https://chromium.googlesource.com/chromium/src.git "$CHROMIUM_DIR/src"
    git -C "$CHROMIUM_DIR/src" checkout --force 151.0.7922.38
fi
"$CHROMIUM_DIR/src/build/install-build-deps.sh" --android --no-prompt

cd "$PROJECT_DIR"
export AFTERBIRD_ARGS_VARIANT=aster-arm64
export AFTERBIRD_CHROMIUM_WORKDIR="$CHROMIUM_DIR"
printf '\nStarting the full ARM64 build. This can take many hours. Keep this SSH window open.\n'
bash chromium-engine/afterbird-source/ci/chromium_android_pipeline.sh --full-build
APK="$CHROMIUM_DIR/src/out/afterbird_production/apks/ChromePublic.apk"
if [ ! -f "$APK" ]; then
    printf '\nBuild ended but the APK was not found at %s\n' "$APK" >&2
    exit 1
fi
printf '\nBUILD FINISHED. APK: %s\n' "$APK"
sha256sum "$APK"
printf 'This candidate has not been tested on a Quest. Without the original signing key, it cannot update an existing differently signed installation.\n'
