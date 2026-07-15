#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIGURATION="${HNS_IOS_CONFIGURATION:-Debug}"
DESTINATION="${HNS_IOS_DESTINATION:-generic/platform=iOS Simulator}"
ACTION="${HNS_IOS_ACTION:-build-for-testing}"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "ERROR: the iOS application requires macOS and Xcode." >&2
  exit 2
fi

case "$ACTION" in
  build-for-testing|test) ;;
  *)
    echo "ERROR: HNS_IOS_ACTION must be build-for-testing or test." >&2
    exit 2
    ;;
esac

if [[ "$ACTION" == "test" && "$DESTINATION" == generic/* ]]; then
  echo "ERROR: HNS_IOS_ACTION=test requires a concrete simulator destination." >&2
  exit 2
fi

"$ROOT_DIR/scripts/build-rust-ios.sh"

xcodebuild \
  -project "$ROOT_DIR/ios/HnsDaneBrowser.xcodeproj" \
  -scheme HnsDaneBrowser \
  -configuration "$CONFIGURATION" \
  -destination "$DESTINATION" \
  CODE_SIGNING_ALLOWED=NO \
  "$ACTION"
