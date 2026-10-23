#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CHROMIUM_SRC="${1:-}"

if [[ -z "${CHROMIUM_SRC}" || ! -d "${CHROMIUM_SRC}/chrome/android" ]]; then
  echo "Usage: $0 /path/to/chromium/src" >&2
  exit 2
fi

GRADLE_CMD="${ASTER_GRADLE_CMD:-gradle}"
export ANDROID_HOME="${ASTER_ANDROID_HOME:-${ANDROID_HOME:-${PROJECT_ROOT}/.build-tmp/android-sdk}}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME}}"
export GRADLE_USER_HOME="${ASTER_GRADLE_USER_HOME:-${PROJECT_ROOT}/.gradle-user}"
if [[ ! -x "${PROJECT_ROOT}/gradlew" ]] && ! command -v "${GRADLE_CMD}" >/dev/null 2>&1; then
  echo "Gradle 9.6+ is required (set ASTER_GRADLE_CMD to its executable)." >&2
  exit 2
fi

cd "${PROJECT_ROOT}"
if [[ -x "${PROJECT_ROOT}/gradlew" ]]; then
  "${PROJECT_ROOT}/gradlew" --no-daemon :aster-engine:stageChromiumIntegration
else
  "${GRADLE_CMD}" --no-daemon :aster-engine:stageChromiumIntegration
fi

STAGE="${PROJECT_ROOT}/aster-engine/build/chromium-stage"
DEST="${CHROMIUM_SRC}/third_party/aster/runtime"
mkdir -p "${DEST}"
rm -rf "${DEST}/dependencies"
cp -a "${STAGE}/aster-engine.aar" "${DEST}/aster-engine.aar"
cp -a "${STAGE}/dependencies" "${DEST}/dependencies"
cp "${STAGE}/dependencies.lock" "${DEST}/dependencies.lock"

python3 "${SCRIPT_DIR}/generate_chromium_gn.py" "${DEST}"

echo "Staged the Gecko runtime into ${DEST}"
