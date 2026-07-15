#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/rust/crates/hns-browser-runtime"
RUST_TOOLCHAIN="1.92.0"

dependency_tree="$(cargo "+$RUST_TOOLCHAIN" tree --locked \
  --manifest-path "$ROOT_DIR/rust/Cargo.toml" \
  --package hns-browser-runtime \
  --prefix none)"

if grep -Eq '^(android-ffi|jni(-sys)?) v[0-9]' <<<"$dependency_tree"; then
  echo "ERROR: hns-browser-runtime must not depend on Android JNI crates." >&2
  grep -E '^(android-ffi|jni(-sys)?) v[0-9]' <<<"$dependency_tree" >&2
  exit 1
fi

if matches="$(grep -RInE \
  --include='Cargo.toml' \
  --include='*.rs' \
  '(^|[^[:alnum:]_])(jni::|JNIEnv|JNIEXPORT|JNICALL|JClass|JObject|JString|JValue|jboolean|jbyteArray|jint|jlong)([^[:alnum:]_]|$)|extern[[:space:]]+"system"|Java_[[:alnum:]_]+' \
  "$RUNTIME_DIR")"; then
  echo "ERROR: hns-browser-runtime contains JNI-specific source or symbols." >&2
  printf '%s\n' "$matches" >&2
  exit 1
fi

echo "hns-browser-runtime boundary check passed"
