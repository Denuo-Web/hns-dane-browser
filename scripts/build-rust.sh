#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_TOOLCHAIN="1.92.0"
cargo "+$RUST_TOOLCHAIN" build --locked --manifest-path "$ROOT_DIR/rust/Cargo.toml" --workspace
