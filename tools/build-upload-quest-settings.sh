#!/usr/bin/env bash
set -Eeuo pipefail
trap 'printf "\nThe build or upload stopped. Please send a photo, but never share a GitHub login code or token.\n" >&2' ERR
TASK_ROOT="$HOME/aster-quest-build"
PROJECT_DIR="$TASK_ROOT/aster-browser-questfixed"
SOURCE_DIR="$TASK_ROOT/chromium/src"
APK="$SOURCE_DIR/out/afterbird_production/apks/ChromePublic.apk"
SDK_TOOLS="$TASK_ROOT/tools/android-sdk/build-tools/37.0.0"
REPO="bprosusupro-rgb/aster-browser-questfixed"
TAG="v5.2.0"
SOURCE_COMMIT="125bb14"
ASSET="AsterBrowser-v5.2.0-Quest-arm64-test.apk"

# Use the exact reviewed build helper, even if the local checkout is older.
TEMP_DIR=$(mktemp -d)
HELPER="$TEMP_DIR/helper.sh"
trap 'rm -rf "$TEMP_DIR"' EXIT
curl -fsSL "https://raw.githubusercontent.com/$REPO/$SOURCE_COMMIT/tools/resume-quest-settings.sh" -o "$HELPER"
bash "$HELPER"

git -C "$PROJECT_DIR" merge-base --is-ancestor "$SOURCE_COMMIT" HEAD
if [ -n "$(git -C "$PROJECT_DIR" status --porcelain --untracked-files=no)" ]; then
    printf '\nThe project checkout has local modifications. Upload stopped; please send a photo.\n' >&2
    exit 1
fi
if ! git -C "$PROJECT_DIR" diff --quiet "$SOURCE_COMMIT" HEAD -- app aster-engine chromium-engine; then
    printf '\nBrowser source has changed since this fix was reviewed. Upload stopped; please send a photo.\n' >&2
    exit 1
fi
BADGING=$("$SDK_TOOLS/aapt" dump badging "$APK")
if ! printf '%s\n' "$BADGING" | grep -q "^package: name='app.aster.browser' versionCode='805000000' versionName='5.0.0'" ||
   ! printf '%s\n' "$BADGING" | grep -Eq "^native-code:.*'arm64-v8a'"; then
    printf '\nUnexpected APK package, version or architecture. Upload stopped.\n' >&2
    exit 1
fi
CERT=$("$SDK_TOOLS/apksigner" verify --print-certs "$APK" | awk '/certificate SHA-256 digest:/ { print $NF; exit }')
if [ "$CERT" != '32a2fc74d731105859e5a85df16d95f102d85b22099b8064c5d8915c61dad1e0' ]; then
    printf '\nThe APK signature differs from the published test APK. Upload stopped.\n' >&2
    exit 1
fi
SHA=$(sha256sum "$APK" | cut -d ' ' -f 1)
if ! command -v gh >/dev/null 2>&1; then
    sudo apt-get update
    sudo apt-get install -y gh
fi
if ! gh auth status --hostname github.com >/dev/null 2>&1; then
    printf '\nGitHub login is needed. Open https://github.com/login/device and enter the code shown below. Do not share the code.\n'
    BROWSER=echo gh auth login --hostname github.com --git-protocol https --web
fi
if [ "$(gh api user --jq .login)" != 'bprosusupro-rgb' ]; then
    printf '\nPlease log in to GitHub as bprosusupro-rgb and try again.\n' >&2
    exit 1
fi
EXPECTED_SHA=$(git -C "$PROJECT_DIR" rev-parse "$SOURCE_COMMIT")
if ! gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    printf '\nCreating the v5.2.0 test release...\n'
    gh release create "$TAG" --repo "$REPO" --target "$EXPECTED_SHA" --prerelease \
        --title 'v5.2.0 Quest test' \
        --notes 'Quest test build with the Chromium settings engine and Aster tools crash fix. This APK has not yet been verified on Quest. Its internal Android version remains 5.0.0 (805000000); v5.2.0 is the release label. The signing key matches the previous fork test APK and differs from upstream v5. YouTube and Spotify loading performance is still under investigation.'
fi
if [ "$(gh release view "$TAG" --repo "$REPO" --json isPrerelease --jq .isPrerelease)" != true ]; then
    printf '\nThe release exists but is not a test release. Upload stopped.\n' >&2
    exit 1
fi
TAG_SHA=$(gh api "repos/$REPO/git/ref/tags/$TAG" --jq .object.sha)
if [ "$TAG_SHA" != "$EXPECTED_SHA" ]; then
    printf '\nThe release tag does not point to the reviewed source. Upload stopped.\n' >&2
    exit 1
fi
EXISTING=$(gh api "repos/$REPO/releases/tags/$TAG" --jq ".assets[] | select(.name == \"$ASSET\") | .digest // \"unknown\"")
if [ -n "$EXISTING" ]; then
    if [ "$EXISTING" != "sha256:$SHA" ]; then
        printf '\nA different APK already uses the settings-fix name. It was not replaced.\n' >&2
        exit 1
    fi
    printf '\nThis APK is already uploaded.\n'
else
    printf '\nUploading the test APK. Keep this window open; the file is large.\n'
    ln -s "$APK" "$TEMP_DIR/$ASSET"
    gh release upload "$TAG" "$TEMP_DIR/$ASSET" --repo "$REPO"
fi
REMOTE=$(gh api "repos/$REPO/releases/tags/$TAG" --jq ".assets[] | select(.name == \"$ASSET\") | .digest // \"\"")
if [ "$REMOTE" != "sha256:$SHA" ]; then
    printf '\nGitHub did not confirm the expected APK checksum. Please send a photo.\n' >&2
    exit 1
fi
if [ "$(gh release view "$TAG" --repo "$REPO" --json isDraft --jq .isDraft)" = true ]; then
    gh release edit "$TAG" --repo "$REPO" --draft=false --prerelease
fi
printf '\nUPLOAD COMPLETE: https://github.com/%s/releases/tag/%s\n' "$REPO" "$TAG"
printf 'File: %s\nSHA-256: %s\n' "$ASSET" "$SHA"
printf 'This is a test APK. Internal version: 5.0.0. Please test on Quest.\n'
