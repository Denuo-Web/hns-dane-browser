#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$ROOT_DIR/android/app/build/generated/rustJniLibs}"
PROFILE="${HNS_RUST_ANDROID_PROFILE:-release}"
EXPECTED_CARGO_NDK_VERSION="${HNS_CARGO_NDK_VERSION:-4.1.2}"
EXPECTED_NDK_VERSION="${HNS_ANDROID_NDK_VERSION:-28.2.13676358}"
RUST_TOOLCHAIN="1.92.0"
CARGO=(cargo "+$RUST_TOOLCHAIN")
RUSTC=(rustc "+$RUST_TOOLCHAIN")

case "$PROFILE" in
  debug|release) ;;
  *)
    echo "ERROR: HNS_RUST_ANDROID_PROFILE must be 'debug' or 'release', not '$PROFILE'." >&2
    exit 2
    ;;
esac

configured_rust_toolchain="$(
  sed -n 's/^[[:space:]]*channel[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' \
    "$ROOT_DIR/rust/rust-toolchain.toml"
)"
if [[ "$configured_rust_toolchain" != "$RUST_TOOLCHAIN" ]]; then
  echo "ERROR: rust/rust-toolchain.toml must pin Rust $RUST_TOOLCHAIN; found '${configured_rust_toolchain:-missing}'." >&2
  exit 2
fi

installed_cargo_version="$("${CARGO[@]}" --version 2>/dev/null || true)"
if [[ "$installed_cargo_version" != "cargo $RUST_TOOLCHAIN "* ]]; then
  echo "ERROR: cargo $RUST_TOOLCHAIN is required; found '${installed_cargo_version:-unavailable}'." >&2
  exit 2
fi
installed_rustc_version="$("${RUSTC[@]}" --version 2>/dev/null || true)"
if [[ "$installed_rustc_version" != "rustc $RUST_TOOLCHAIN "* ]]; then
  echo "ERROR: rustc $RUST_TOOLCHAIN is required; found '${installed_rustc_version:-unavailable}'." >&2
  exit 2
fi

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "ERROR: cargo-ndk is required. Install with: cargo +$RUST_TOOLCHAIN install cargo-ndk --version $EXPECTED_CARGO_NDK_VERSION --locked" >&2
  exit 2
fi

installed_cargo_ndk_version="$("${CARGO[@]}" ndk --version 2>/dev/null || true)"
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

  # Keep enough DWARF for AGP to produce Play Console native debug symbols.
  # Stable remapped paths avoid leaking the builder's checkout and tool homes.
  blocked_flag_variables=()
  while IFS='=' read -r variable_name _; do
    case "$variable_name" in
      RUSTUP_TOOLCHAIN|RUSTFLAGS|CARGO_ENCODED_RUSTFLAGS|CARGO_BUILD_RUSTFLAGS|CARGO_BUILD_RUSTC|CARGO_BUILD_RUSTC_WRAPPER|CARGO_BUILD_RUSTC_WORKSPACE_WRAPPER|CARGO_PROFILE_RELEASE_*|CARGO_TARGET_*_RUSTFLAGS|CARGO_TARGET_*_LINKER|RUSTC|RUSTC_WRAPPER|RUSTC_WORKSPACE_WRAPPER|CC|CC_*|*_CC|CXX|CXX_*|*_CXX|AR|AR_*|*_AR|LD|HOST_LD|TARGET_LD|CFLAGS|CFLAGS_*|*_CFLAGS|CXXFLAGS|CXXFLAGS_*|*_CXXFLAGS|CPPFLAGS|CPPFLAGS_*|*_CPPFLAGS|LDFLAGS|LDFLAGS_*|*_LDFLAGS|ARFLAGS|ARFLAGS_*|*_ARFLAGS|ASFLAGS|ASFLAGS_*|*_ASFLAGS)
        blocked_flag_variables+=("$variable_name")
        ;;
    esac
  done < <(env)
  if [[ ${#blocked_flag_variables[@]} -ne 0 ]]; then
    printf 'ERROR: release builds reject caller-supplied toolchain or compiler overrides: %s\n' \
      "${blocked_flag_variables[*]}" >&2
    exit 2
  fi

  HOME_DIR="${HOME:?HOME must be set}"
  CARGO_HOME_DIR="${CARGO_HOME:-$HOME_DIR/.cargo}"
  RUSTUP_HOME_DIR="${RUSTUP_HOME:-$HOME_DIR/.rustup}"
  release_rustflags=(
    "--remap-path-prefix=$ROOT_DIR=/build/source"
    "--remap-path-prefix=$CARGO_HOME_DIR=/build/cargo"
    "--remap-path-prefix=$RUSTUP_HOME_DIR=/build/rustup"
    "--remap-path-prefix=$NDK_DIR=/build/ndk"
    "--remap-path-prefix=$HOME_DIR=/build/home"
    "-C"
    "link-arg=-Wl,--build-id=sha1"
  )
  release_cflags=()
  for path_mapping in \
    "$ROOT_DIR=/build/source" \
    "$CARGO_HOME_DIR=/build/cargo" \
    "$RUSTUP_HOME_DIR=/build/rustup" \
    "$NDK_DIR=/build/ndk" \
    "$HOME_DIR=/build/home"; do
    release_cflags+=(
      "-ffile-prefix-map=$path_mapping"
      "-fdebug-prefix-map=$path_mapping"
      "-fmacro-prefix-map=$path_mapping"
    )
  done
  for compiler_flag in "${release_rustflags[@]}" "${release_cflags[@]}"; do
    if [[ "$compiler_flag" =~ [[:space:]] ]]; then
      echo "ERROR: release build path cannot contain whitespace: $compiler_flag" >&2
      exit 2
    fi
  done
  export RUSTFLAGS="${release_rustflags[*]}"
  export CFLAGS="${release_cflags[*]}"
  export CXXFLAGS="$CFLAGS"
  export CFLAGS_aarch64_linux_android="$CFLAGS"
  export CFLAGS_x86_64_linux_android="$CFLAGS"
  export CXXFLAGS_aarch64_linux_android="$CXXFLAGS"
  export CXXFLAGS_x86_64_linux_android="$CXXFLAGS"
fi

cd "$ROOT_DIR/rust"
"${CARGO[@]}" "${ARGS[@]}" --locked

for abi in arm64-v8a x86_64; do
  library="$OUT_DIR/$abi/libhns_dane_browser_ffi.so"
  if [[ ! -s "$library" ]]; then
    echo "ERROR: cargo-ndk did not produce the required library: $library" >&2
    exit 1
  fi
  if [[ "$PROFILE" == "release" ]] && \
    LC_ALL=C grep -aFq -e "$ROOT_DIR" -e "${HOME:?HOME must be set}" -e "$NDK_DIR" "$library"; then
    echo "ERROR: release native library exposes a builder checkout, home, or NDK path: $library" >&2
    exit 1
  fi
done
