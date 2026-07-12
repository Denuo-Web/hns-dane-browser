#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FUZZ_MANIFEST="$ROOT_DIR/rust/fuzz/Cargo.toml"
RUST_TOOLCHAIN="1.92.0"
CARGO=(cargo "+$RUST_TOOLCHAIN")

"${CARGO[@]}" fmt --manifest-path "$FUZZ_MANIFEST" --all -- --check
"${CARGO[@]}" check --locked --manifest-path "$FUZZ_MANIFEST" --bins
