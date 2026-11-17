#!/usr/bin/env bash
# Local verification used by CI and release checks.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ABI="${ABIS:-arm64-v8a}"
GRADLE="${GRADLE_BIN:-./gradlew}"

./scripts/build-native-deps.sh $ABI
$GRADLE --no-daemon -Pabis="$ABI" \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest
