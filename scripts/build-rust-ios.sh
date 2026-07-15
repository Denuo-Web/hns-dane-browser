#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_TOOLCHAIN="1.92.0"
PROFILE="${HNS_RUST_IOS_PROFILE:-ios-release}"
OUT_DIR="${1:-$ROOT_DIR/build/apple}"
TARGET_DIR="$OUT_DIR/target"
FRAMEWORK_PATH="$OUT_DIR/HnsBrowserRuntime.xcframework"
INCLUDE_DIR="$ROOT_DIR/rust/crates/ios-ffi/include"
LIBRARY_NAME="libhns_browser_ios.a"
TARGETS=(
  aarch64-apple-ios
  aarch64-apple-ios-sim
  x86_64-apple-ios
)

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "ERROR: Apple Rust libraries require macOS and the Apple SDKs." >&2
  exit 2
fi

case "$PROFILE" in
  dev|release|ios-release) ;;
  *)
    echo "ERROR: HNS_RUST_IOS_PROFILE must be dev, release, or ios-release." >&2
    exit 2
    ;;
esac

case "$OUT_DIR" in
  "$ROOT_DIR"/build/*) ;;
  *)
    echo "ERROR: refusing to write Apple build output outside $ROOT_DIR/build: $OUT_DIR" >&2
    exit 2
    ;;
esac

for command in cargo rustc rustup xcodebuild xcrun; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "ERROR: required command is unavailable: $command" >&2
    exit 2
  fi
done

configured_toolchain="$(
  sed -n 's/^[[:space:]]*channel[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' \
    "$ROOT_DIR/rust/rust-toolchain.toml"
)"
if [[ "$configured_toolchain" != "$RUST_TOOLCHAIN" ]]; then
  echo "ERROR: rust/rust-toolchain.toml must pin Rust $RUST_TOOLCHAIN." >&2
  exit 2
fi

installed_cargo_version="$(cargo "+$RUST_TOOLCHAIN" --version 2>/dev/null || true)"
installed_rustc_version="$(rustc "+$RUST_TOOLCHAIN" --version 2>/dev/null || true)"
if [[ "$installed_cargo_version" != "cargo $RUST_TOOLCHAIN "* ]] || \
  [[ "$installed_rustc_version" != "rustc $RUST_TOOLCHAIN "* ]]; then
  echo "ERROR: cargo and rustc $RUST_TOOLCHAIN are required." >&2
  exit 2
fi

if [[ ! -s "$INCLUDE_DIR/hns_browser.h" ]] || [[ ! -s "$INCLUDE_DIR/module.modulemap" ]]; then
  echo "ERROR: the committed C header and module map are required in $INCLUDE_DIR." >&2
  exit 2
fi

installed_targets="$(rustup target list --toolchain "$RUST_TOOLCHAIN" --installed)"
for target in "${TARGETS[@]}"; do
  if ! grep -Fxq "$target" <<<"$installed_targets"; then
    echo "ERROR: missing Rust target $target; install it with rustup target add --toolchain $RUST_TOOLCHAIN $target" >&2
    exit 2
  fi
done

rm -rf -- "$OUT_DIR/device" "$OUT_DIR/simulator" "$FRAMEWORK_PATH"
mkdir -p -- "$OUT_DIR/device" "$OUT_DIR/simulator" "$TARGET_DIR"

profile_args=()
if [[ "$PROFILE" != "dev" ]]; then
  profile_args=(--profile "$PROFILE")
fi

for target in "${TARGETS[@]}"; do
  IPHONEOS_DEPLOYMENT_TARGET=17.0 \
    CARGO_TARGET_DIR="$TARGET_DIR" \
    cargo "+$RUST_TOOLCHAIN" build \
      --locked \
      --manifest-path "$ROOT_DIR/rust/Cargo.toml" \
      --package ios-ffi \
      --target "$target" \
      "${profile_args[@]}"
done

profile_dir="$PROFILE"
if [[ "$PROFILE" == "dev" ]]; then
  profile_dir="debug"
fi

device_library="$TARGET_DIR/aarch64-apple-ios/$profile_dir/$LIBRARY_NAME"
simulator_arm_library="$TARGET_DIR/aarch64-apple-ios-sim/$profile_dir/$LIBRARY_NAME"
simulator_x86_library="$TARGET_DIR/x86_64-apple-ios/$profile_dir/$LIBRARY_NAME"
for library in "$device_library" "$simulator_arm_library" "$simulator_x86_library"; do
  if [[ ! -s "$library" ]]; then
    echo "ERROR: Rust did not produce the expected Apple static library: $library" >&2
    exit 1
  fi
done

cp -- "$device_library" "$OUT_DIR/device/$LIBRARY_NAME"
xcrun lipo -create \
  "$simulator_arm_library" \
  "$simulator_x86_library" \
  -output "$OUT_DIR/simulator/$LIBRARY_NAME"

xcodebuild -create-xcframework \
  -library "$OUT_DIR/device/$LIBRARY_NAME" \
  -headers "$INCLUDE_DIR" \
  -library "$OUT_DIR/simulator/$LIBRARY_NAME" \
  -headers "$INCLUDE_DIR" \
  -output "$FRAMEWORK_PATH"

if [[ ! -d "$FRAMEWORK_PATH" ]]; then
  echo "ERROR: xcodebuild did not create $FRAMEWORK_PATH" >&2
  exit 1
fi

echo "Created $FRAMEWORK_PATH"
