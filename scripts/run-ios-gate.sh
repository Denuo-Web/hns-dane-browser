#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_TOOLCHAIN="1.92.0"
IOS_SDK_VERSION="26.5"
APPLE_TARGETS=(
  aarch64-apple-ios
  aarch64-apple-ios-sim
  x86_64-apple-ios
)

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

if [[ "$(uname -s)" != "Darwin" ]]; then
  fail "the complete iOS gate requires macOS and Xcode."
fi

for command in python3 rustup xcode-select xcodebuild xcrun; do
  command -v "$command" >/dev/null 2>&1 || fail "required command is unavailable: $command"
done

developer_dir="$(xcode-select --print-path)"
[[ -x "$developer_dir/usr/bin/xcodebuild" ]] ||
  fail "the selected developer directory does not contain xcodebuild: $developer_dir"
export DEVELOPER_DIR="$developer_dir"
export CARGO_INCREMENTAL=0

xcodebuild -version
xcode_version="$(xcodebuild -version | sed -n '1s/^Xcode //p')"
case "$xcode_version" in
  26.5|26.5.*|26.6|26.6.*) ;;
  *) fail "Xcode 26.5 or 26.6 is required; selected $xcode_version." ;;
esac

iphoneos_sdk="$(xcrun --sdk iphoneos --show-sdk-version)"
simulator_sdk="$(xcrun --sdk iphonesimulator --show-sdk-version)"
printf 'iphoneos SDK %s\niphonesimulator SDK %s\n' \
  "$iphoneos_sdk" "$simulator_sdk"
[[ "$iphoneos_sdk" == "$IOS_SDK_VERSION" ]] ||
  fail "iphoneos SDK $IOS_SDK_VERSION is required; selected $iphoneos_sdk."
[[ "$simulator_sdk" == "$IOS_SDK_VERSION" ]] ||
  fail "iphonesimulator SDK $IOS_SDK_VERSION is required; selected $simulator_sdk."

rustup toolchain install "$RUST_TOOLCHAIN" \
  --profile minimal \
  --component rustfmt clippy
rustup target add --toolchain "$RUST_TOOLCHAIN" "${APPLE_TARGETS[@]}"

cd "$ROOT_DIR"
./scripts/check-version-consistency.sh
./scripts/check-runtime-boundaries.sh
python3 ./scripts/test_select_ios_simulator.py

abi_target_dir="$(mktemp -d "${TMPDIR:-/tmp}/hns-ios-abi.XXXXXX")"
cleanup() {
  rm -rf -- "$abi_target_dir"
}
trap cleanup EXIT
HNS_IOS_ABI_TARGET_DIR="$abi_target_dir" ./scripts/check-ios-abi.sh
cleanup
trap - EXIT

simulator_id="$(
  xcrun simctl list devices available -j |
    python3 ./scripts/select_ios_simulator.py --runtime "$IOS_SDK_VERSION"
)"
[[ -n "$simulator_id" ]] || fail "the iOS simulator selector returned no device."
printf 'Selected iOS %s simulator: %s\n' "$IOS_SDK_VERSION" "$simulator_id"
xcrun simctl boot "$simulator_id" >/dev/null 2>&1 || true
xcrun simctl bootstatus "$simulator_id" -b

HNS_RUST_IOS_CLEAN_TARGET=1 \
  HNS_IOS_ACTION=test \
  HNS_IOS_DESTINATION="platform=iOS Simulator,id=$simulator_id" \
  ./scripts/build-ios.sh

HNS_IOS_REUSE_XCFRAMEWORK=1 \
  HNS_IOS_ACTION=build \
  HNS_IOS_CONFIGURATION=Release \
  HNS_IOS_DESTINATION="generic/platform=iOS" \
  ./scripts/build-ios.sh

echo "iOS gate passed: ABI, XCFramework, simulator tests, and unsigned arm64 device link."
