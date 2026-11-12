#!/usr/bin/env bash
# One-time uploader for the 2026-11 Quest candidate on this fork. It leaves the
# release as a draft and refuses to replace an existing asset.
set -Eeuo pipefail
trap 'printf "\nThe upload stopped. Please send a photo of the error; do not send any login code or token.\n" >&2' ERR

TASK_ROOT="$HOME/aster-quest-build"
PROJECT_DIR="$TASK_ROOT/aster-browser-questfixed"
SOURCE_DIR="$TASK_ROOT/chromium/src"
APK="$SOURCE_DIR/out/afterbird_production/apks/ChromePublic.apk"
SDK_TOOLS="$TASK_ROOT/tools/android-sdk/build-tools/37.0.0"
REPO="bprosusupro-rgb/aster-browser-questfixed"
RELEASE_ID=441005696
RELEASE_TAG="quest-reopen-source-candidate-2026-11-10"
EXPECTED_SOURCE="d808c20d0fa520857b3a02b0b3218e634ee9c9fe"
UPSTREAM_V5_CERT="59b7beae054026bd0269fcedb68a3144960bf227528f366b92cf878caca2e79f"

if [ ! -f "$APK" ] || [ ! -d "$PROJECT_DIR/.git" ] || [ ! -d "$SOURCE_DIR/.git" ]; then
    printf 'The finished APK or its original build checkout was not found. Please send a photo.\n' >&2
    exit 1
fi
if pgrep -u "$(id -u)" -f '[/]siso/cipd/siso|[a]utoninja|[n]inja -C' >/dev/null; then
    printf 'A build is still running. Wait for it to finish before uploading.\n' >&2
    exit 1
fi
ACTUAL_SOURCE=$(git -C "$PROJECT_DIR" rev-parse HEAD)
if [ "$ACTUAL_SOURCE" != "$EXPECTED_SOURCE" ]; then
    printf 'The build checkout is at %s, but this draft targets %s. Please send a photo.\n' "$ACTUAL_SOURCE" "$EXPECTED_SOURCE" >&2
    exit 1
fi
if [ -n "$(git -C "$PROJECT_DIR" status --porcelain --untracked-files=no)" ]; then
    printf 'The project checkout has local modifications; its exact source needs review.\n' >&2
    exit 1
fi
CHROMIUM_HEAD=$(git -C "$SOURCE_DIR" rev-parse HEAD)
CHROMIUM_TAG=$(git -C "$SOURCE_DIR" rev-parse '151.0.7922.38^{commit}')
if [ "$CHROMIUM_HEAD" != "$CHROMIUM_TAG" ]; then
    printf 'The Chromium checkout is not at the expected pinned revision.\n' >&2
    exit 1
fi
PATCH="$PROJECT_DIR/chromium-engine/afterbird-source/patches/m151/0039-aster-chromium151-build-compat.patch"
if ! git -C "$SOURCE_DIR" apply --reverse --check "$PATCH"; then
    printf 'The expected Chromium compatibility fix could not be verified.\n' >&2
    exit 1
fi
if [ "$APK" -ot "$SOURCE_DIR/chrome/android/java/src/org/chromium/chrome/browser/ntp/NewTabPageCoordinator.java" ] ||
   [ "$APK" -ot "$SOURCE_DIR/chrome/android/java/src/org/chromium/chrome/browser/settings/MainSettings.java" ]; then
    printf 'The APK is older than the corrected source files. Complete the build before uploading.\n' >&2
    exit 1
fi

printf '\nChecking APK package, CPU architecture, signature and checksum...\n'
BADGING=$("$SDK_TOOLS/aapt" dump badging "$APK")
printf '%s\n' "$BADGING" | grep -E '^(package:|native-code:)'
if ! printf '%s\n' "$BADGING" | grep -q "^package: name='app.aster.browser' versionCode='805000000' versionName='5.0.0'" ||
   ! printf '%s\n' "$BADGING" | grep -Eq "^native-code:.*'arm64-v8a'"; then
    printf 'This APK does not have the expected Aster package, version and ARM64 architecture.\n' >&2
    exit 1
fi
SIGNER_INFO=$("$SDK_TOOLS/apksigner" verify --print-certs "$APK")
SIGNER_SHA=$(printf '%s\n' "$SIGNER_INFO" | awk '/certificate SHA-256 digest:/ {print $NF; exit}')
if ! [[ "$SIGNER_SHA" =~ ^[0-9a-fA-F]{64}$ ]]; then
    printf 'Could not verify the APK signing certificate.\n' >&2
    exit 1
fi
APK_SHA=$(sha256sum "$APK")
APK_SHA=${APK_SHA%% *}
printf 'APK SHA-256: %s\nSigner SHA-256: %s\n' "$APK_SHA" "$SIGNER_SHA"
if [ "$SIGNER_SHA" = "$UPSTREAM_V5_CERT" ]; then
    SIGNER_RESULT="Matches the certificate on the upstream v5.0.0 ARM64 APK. Update installation still requires testing against the installed app."
else
    SIGNER_RESULT="Differs from the certificate on the upstream v5.0.0 ARM64 APK. It cannot directly update that release without compatible signing; do not uninstall the existing app without backing up its data."
fi
printf '%s\n' "$SIGNER_RESULT"

if ! command -v gh >/dev/null 2>&1; then
    printf '\nInstalling the GitHub command-line tool...\n'
    sudo apt-get update
    sudo apt-get install -y gh
fi
if ! gh auth status --hostname github.com >/dev/null 2>&1; then
    printf '\nGitHub login is required. A one-time code will appear below.\n'
    printf 'Open https://github.com/login/device in your browser, enter the code, and approve access.\n'
    printf 'Do not send the code or a token to anyone. Return to this window when done.\n\n'
    BROWSER=echo gh auth login --hostname github.com --git-protocol https --web
fi
LOGIN=$(gh api user --jq .login)
if [ "$LOGIN" != "bprosusupro-rgb" ]; then
    printf 'Signed in as %s; the draft belongs to bprosusupro-rgb. Switch account and retry.\n' "$LOGIN" >&2
    exit 1
fi
RELEASE_PATH="repos/$REPO/releases/$RELEASE_ID"
DRAFT=$(gh api "$RELEASE_PATH" --jq .draft)
TARGET=$(gh api "$RELEASE_PATH" --jq .target_commitish)
TAG=$(gh api "$RELEASE_PATH" --jq .tag_name)
if [ "$DRAFT" != true ] || [ "$TARGET" != "$EXPECTED_SOURCE" ] || [ "$TAG" != "$RELEASE_TAG" ]; then
    printf 'The release is not the expected draft and source. Nothing was uploaded.\n' >&2
    exit 1
fi
EXISTING_COUNT=$(gh api "$RELEASE_PATH" --jq '[.assets[] | select(.name == "ChromePublic.apk")] | length')
if [ "$EXISTING_COUNT" != 0 ]; then
    EXISTING_DIGEST=$(gh api "$RELEASE_PATH" --jq '.assets[] | select(.name == "ChromePublic.apk") | .digest // ""')
    if [ "$EXISTING_DIGEST" != "sha256:$APK_SHA" ]; then
        printf 'A different or unverifiable ChromePublic.apk is already attached; it was not replaced.\n' >&2
        exit 1
    fi
    printf '\nThe same APK is already uploaded.\n'
else
    printf '\nUploading the APK to your draft release. This may take a while; keep this window open.\n'
    gh release upload "$RELEASE_TAG" "$APK" --repo "$REPO"
fi
# Verify GitHub recorded the same asset and bytes before describing it as ready.
REMOTE_DIGEST=$(gh api "$RELEASE_PATH" --jq '.assets[] | select(.name == "ChromePublic.apk") | .digest // ""')
if [ -z "$REMOTE_DIGEST" ]; then
    REMOTE_RESULT="GitHub did not return a checksum; verify the downloaded asset before publication."
elif [ "$REMOTE_DIGEST" != "sha256:$APK_SHA" ]; then
    printf 'The upload completed but GitHub did not report the expected checksum. Please send a photo.\n' >&2
    exit 1
else
    REMOTE_RESULT="GitHub reported the same SHA-256 after upload."
fi
NOTES=$(mktemp)
trap 'rm -f "$NOTES"' EXIT
cat > "$NOTES" <<NOTES_EOF
# Quest 3S reopening — test candidate

This is a **draft test candidate**. The ARM64 APK built successfully, but it has not yet been tested on a physical Quest. The reported reopening hang is not yet confirmed fixed.

- File: \`ChromePublic.apk\`
- Package: \`app.aster.browser\`
- Architecture: \`arm64-v8a\`
- Source commit: \`$ACTUAL_SOURCE\`
- APK SHA-256: \`$APK_SHA\`
- Signing certificate SHA-256: \`$SIGNER_SHA\`
- Signature comparison: $SIGNER_RESULT

The APK package and signature were verified on the build VM. $REMOTE_RESULT Version information: $(printf '%s\n' "$BADGING" | grep '^package:' | head -1)

Changes include startup/persistence hardening, Chromium patch repairs and Android compatibility fixes. Before publishing, install and test on a physical Quest, including closing and reopening both engines. Preserve existing browser data if the APK cannot update the installed version.

[Quest reopening review](https://github.com/$REPO/blob/$ACTUAL_SOURCE/docs/QUEST_REOPEN_REVIEW.md)
NOTES_EOF
# Preserve the intended tag explicitly when updating a draft release.
gh api --method PATCH "$RELEASE_PATH" -f "tag_name=$RELEASE_TAG" -f "target_commitish=$EXPECTED_SOURCE" -F "body=@$NOTES" -F draft=true -F prerelease=true --jq .html_url
printf '\nUPLOAD COMPLETE. Your APK is in the GitHub draft release. It has not been published.\n'
