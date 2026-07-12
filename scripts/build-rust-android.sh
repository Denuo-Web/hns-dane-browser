#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$ROOT_DIR/android/app/build/generated/rustJniLibs}"
PROFILE="${HNS_RUST_ANDROID_PROFILE:-release}"
EXPECTED_CARGO_NDK_VERSION="${HNS_CARGO_NDK_VERSION:-4.1.2}"
EXPECTED_NDK_VERSION="${HNS_ANDROID_NDK_VERSION:-}"

case "$PROFILE" in
  debug|release) ;;
  *)
    echo "ERROR: HNS_RUST_ANDROID_PROFILE must be 'debug' or 'release', not '$PROFILE'." >&2
    exit 2
    ;;
esac

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "ERROR: cargo-ndk is required. Install with: cargo install cargo-ndk --version $EXPECTED_CARGO_NDK_VERSION --locked" >&2
  exit 2
fi

installed_cargo_ndk_version="$(cargo ndk --version 2>/dev/null || true)"
if [[ "$installed_cargo_ndk_version" != "cargo-ndk $EXPECTED_CARGO_NDK_VERSION" ]]; then
  echo "ERROR: cargo-ndk $EXPECTED_CARGO_NDK_VERSION is required; found '${installed_cargo_ndk_version:-unknown}'." >&2
  exit 2
fi

NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_DIR" ]]; then
  echo "ERROR: ANDROID_NDK_HOME or ANDROID_NDK_ROOT must point to the Android NDK." >&2
  exit 2
fi
if [[ ! -d "$NDK_DIR" ]]; then
  echo "ERROR: Android NDK directory does not exist: $NDK_DIR" >&2
  exit 2
fi
NDK_DIR="$(cd "$NDK_DIR" && pwd -P)"

NDK_PROPERTIES="$NDK_DIR/source.properties"
installed_ndk_version="$(sed -n 's/^Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$NDK_PROPERTIES" 2>/dev/null | head -n 1)"
if [[ -z "$installed_ndk_version" ]]; then
  echo "ERROR: unable to read the Android NDK version from $NDK_PROPERTIES." >&2
  exit 2
fi
if [[ -n "$EXPECTED_NDK_VERSION" && "$installed_ndk_version" != "$EXPECTED_NDK_VERSION" ]]; then
  echo "ERROR: Android NDK $EXPECTED_NDK_VERSION is required; found '${installed_ndk_version:-unknown}' at $NDK_DIR." >&2
  exit 2
fi

mkdir -p -- "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd -P)"
case "$OUT_DIR" in
  "$ROOT_DIR"/android/app/build/*) ;;
  *)
    echo "ERROR: refusing to clean native output outside android/app/build: $OUT_DIR" >&2
    exit 2
    ;;
esac
find "$OUT_DIR" -type f -name '*.so' -delete

ARGS=(
  ndk
  -t arm64-v8a
  -t x86_64
  -P 34
  -o "$OUT_DIR"
  build
  -p android-ffi
)

if [[ "$PROFILE" == "release" ]]; then
  ARGS+=(--release)
fi

cd "$ROOT_DIR/rust"
cargo "${ARGS[@]}" --locked

for abi in arm64-v8a x86_64; do
  library="$OUT_DIR/$abi/libhns_dane_browser_ffi.so"
  if [[ ! -s "$library" ]]; then
    echo "ERROR: cargo-ndk did not produce the required library: $library" >&2
    exit 1
  fi
done

if [[ "$PROFILE" == "release" ]]; then
  mapfile -t objcopy_candidates < <(
    find "$NDK_DIR/toolchains/llvm/prebuilt" -type f -path '*/bin/llvm-objcopy' -perm -111 -print | sort
  )
  if [[ ${#objcopy_candidates[@]} -ne 1 || ! -x "${objcopy_candidates[0]}" ]]; then
    echo "ERROR: expected one executable llvm-objcopy under $NDK_DIR/toolchains/llvm/prebuilt." >&2
    exit 2
  fi
  OBJCOPY="${objcopy_candidates[0]}"
  find "$OUT_DIR" -type f -name '*.so' -print0 |
    while IFS= read -r -d '' library; do
      "$OBJCOPY" --strip-unneeded "$library"
    done
fi
