#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root_dir"

android_gradle="android/app/build.gradle.kts"
rust_manifest="rust/Cargo.toml"
ios_project_definition="ios/project.yml"
ios_info_plist="ios/HnsDaneBrowser/Support/Info.plist"
ios_xcode_project="ios/HnsDaneBrowser.xcodeproj/project.pbxproj"
diagnostic_test="android/app/src/test/java/com/denuoweb/hnsdane/ui/DiagnosticReportTest.kt"

android_version="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' "$android_gradle")"
android_build="$(sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\).*/\1/p' "$android_gradle")"
rust_version="$(sed -n 's/^version = "\([^"]*\)".*/\1/p' "$rust_manifest" | head -n 1)"
ios_version="$(sed -n 's/^[[:space:]]*MARKETING_VERSION: \([^[:space:]]*\).*/\1/p' "$ios_project_definition")"
ios_build="$(sed -n 's/^[[:space:]]*CURRENT_PROJECT_VERSION: \([0-9][0-9]*\).*/\1/p' "$ios_project_definition")"

if [[ -z "$android_version" || -z "$android_build" || -z "$rust_version" ||
  -z "$ios_version" || -z "$ios_build" ]]; then
  echo "Could not read the Android, Rust, or Apple version values." >&2
  exit 1
fi

# Platform app releases and the shared Rust engine have independent lifecycles.
# Validate every component against its own release metadata instead of forcing an
# Android-only maintenance release to masquerade as a Rust and Apple release.
missing=0

android_expected_files=(
  "$android_gradle"
  "CHANGELOG.md"
  "scripts/play-upload-closed-testing.sh"
  "dist/play-store/metadata/README.md"
  "dist/play-store/metadata/en-US/release-notes.txt"
  "docs/play-store-readiness.md"
  "docs/production-readiness-audit.md"
  "docs/supply-chain-audit.md"
  "$diagnostic_test"
)

for file in "${android_expected_files[@]}"; do
  if ! grep -Fq "$android_version" "$file"; then
    echo "Missing Android version $android_version in $file" >&2
    missing=1
  fi
done

android_artifact="hns-dane-browser-v${android_version}-play-upload-signed.aab"
android_exact_checks=(
  "${android_gradle}:versionCode = ${android_build}"
  "${android_gradle}:versionName = \"${android_version}\""
  "CHANGELOG.md:## ${android_version} -"
  "scripts/play-upload-closed-testing.sh:${android_artifact}"
  "scripts/play-upload-closed-testing.sh:HNS DANE Browser ${android_version}"
  "dist/play-store/metadata/README.md:${android_version} release notes"
  "dist/play-store/metadata/README.md:${android_artifact}"
  "dist/play-store/metadata/en-US/release-notes.txt:${android_version} "
  "${diagnostic_test}:debug ${android_version} (${android_build})"
)

for check in "${android_exact_checks[@]}"; do
  file="${check%%:*}"
  pattern="${check#*:}"
  if ! grep -Fq "$pattern" "$file"; then
    echo "Missing expected Android version pattern in $file: $pattern" >&2
    missing=1
  fi
done

android_current_only_files=(
  "scripts/play-upload-closed-testing.sh"
  "dist/play-store/metadata/README.md"
  "dist/play-store/metadata/en-US/release-notes.txt"
)

for file in "${android_current_only_files[@]}"; do
  while IFS= read -r found_version; do
    if [[ "$found_version" != "$android_version" ]]; then
      echo "Unexpected Android release version $found_version in $file; expected $android_version." >&2
      missing=1
    fi
  done < <(grep -Eo '0\.[0-9]+\.[0-9]+' "$file" | sort -u)
done

rust_exact_checks=(
  "${rust_manifest}:version = \"${rust_version}\""
  "${diagnostic_test}:hns-dane-browser-rust-core/${rust_version}"
  "rust/Cargo.lock:version = \"${rust_version}\""
  "rust/fuzz/Cargo.lock:version = \"${rust_version}\""
  "tools/hns-header-snapshot-exporter/Cargo.lock:version = \"${rust_version}\""
)

for check in "${rust_exact_checks[@]}"; do
  file="${check%%:*}"
  pattern="${check#*:}"
  if ! grep -Fq "$pattern" "$file"; then
    echo "Missing expected Rust version pattern in $file: $pattern" >&2
    missing=1
  fi
done

ios_exact_checks=(
  "${ios_project_definition}:MARKETING_VERSION: ${ios_version}"
  "${ios_project_definition}:CURRENT_PROJECT_VERSION: ${ios_build}"
  "${ios_info_plist}:<string>${ios_version}</string>"
  "${ios_info_plist}:<string>${ios_build}</string>"
  "${ios_xcode_project}:MARKETING_VERSION = ${ios_version};"
  "${ios_xcode_project}:CURRENT_PROJECT_VERSION = ${ios_build};"
)

for check in "${ios_exact_checks[@]}"; do
  file="${check%%:*}"
  pattern="${check#*:}"
  if ! grep -Fq "$pattern" "$file"; then
    echo "Missing expected Apple version pattern in $file: $pattern" >&2
    missing=1
  fi
done

ios_current_only_files=(
  "$ios_project_definition"
  "$ios_info_plist"
  "$ios_xcode_project"
)

for file in "${ios_current_only_files[@]}"; do
  while IFS= read -r found_version; do
    if [[ "$found_version" != "$ios_version" ]]; then
      echo "Unexpected Apple release version $found_version in $file; expected $ios_version." >&2
      missing=1
    fi
  done < <(grep -Eo '0\.[0-9]+\.[0-9]+' "$file" | sort -u)
done

exit "$missing"
