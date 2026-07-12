#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

EXPECTED_WRAPPER_JAR_SHA256="497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"
EXPECTED_DISTRIBUTION_SHA256="9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14"
EXPECTED_DISTRIBUTION_URL='https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip'
RUST_TOOLCHAIN="1.92.0"
CARGO=(cargo "+$RUST_TOOLCHAIN")
WRAPPER_JAR="android/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_PROPERTIES="android/gradle/wrapper/gradle-wrapper.properties"

actual_wrapper_jar_sha256="$(sha256sum "$WRAPPER_JAR" | awk '{print $1}')"
if [[ "$actual_wrapper_jar_sha256" != "$EXPECTED_WRAPPER_JAR_SHA256" ]]; then
  echo "Unexpected Gradle wrapper JAR SHA-256: $actual_wrapper_jar_sha256" >&2
  exit 1
fi

actual_distribution_sha256="$(sed -n 's/^distributionSha256Sum=//p' "$WRAPPER_PROPERTIES")"
if [[ "$actual_distribution_sha256" != "$EXPECTED_DISTRIBUTION_SHA256" ]]; then
  echo "Unexpected or missing Gradle distribution SHA-256: ${actual_distribution_sha256:-missing}" >&2
  exit 1
fi

actual_distribution_url="$(sed -n 's/^distributionUrl=//p' "$WRAPPER_PROPERTIES")"
if [[ "$actual_distribution_url" != "$EXPECTED_DISTRIBUTION_URL" ]]; then
  echo "Unexpected Gradle distribution URL: ${actual_distribution_url:-missing}" >&2
  exit 1
fi

required_supply_chain_files=(
  "android/app/gradle.lockfile"
  "android/settings-gradle.lockfile"
  "android/gradle/verification-metadata.xml"
  "rust/Cargo.lock"
  "rust/fuzz/Cargo.lock"
  "tools/hns-header-snapshot-exporter/Cargo.lock"
)
for file in "${required_supply_chain_files[@]}"; do
  if [[ ! -s "$file" ]]; then
    echo "Required dependency lock or verification file is missing: $file" >&2
    exit 1
  fi
done

while IFS= read -r -d '' tracked_file; do
  base_name="${tracked_file##*/}"
  case "$base_name" in
    .env|.env.*|.envrc|local.properties|keystore.properties|signing.properties|release.properties|google-services.json|*.keystore|*.jks|*.p12|*.pfx|*.pkcs12|*.kdbx|*.pem|*.key|*.asc|service-account*.json|credentials*.json|firebase-adminsdk*.json)
      echo "Potential secret-bearing file must not be tracked: $tracked_file" >&2
      exit 1
      ;;
  esac
done < <(git ls-files -z)

if git grep -IEn \
  '(BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|AIza[0-9A-Za-z_-]{35}|gh[pousr]_[A-Za-z0-9_]{20,}|sk-(proj-)?[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16})' \
  -- . ':!scripts/verify-supply-chain.sh'; then
  echo "Potential high-confidence secret found in a tracked file." >&2
  exit 1
fi

shopt -s nullglob
workflow_files=(.github/workflows/*.yml .github/workflows/*.yaml)
shopt -u nullglob
if [[ ${#workflow_files[@]} -eq 0 ]]; then
  echo "No GitHub Actions workflows were found." >&2
  exit 1
fi

while IFS= read -r action_reference; do
  action_reference="${action_reference#\"}"
  action_reference="${action_reference%\"}"
  action_reference="${action_reference#\'}"
  action_reference="${action_reference%\'}"
  if [[ "$action_reference" == ./* ]]; then
    continue
  fi
  if [[ ! "$action_reference" =~ ^[-A-Za-z0-9_.]+/[-A-Za-z0-9_.]+(/[-A-Za-z0-9_.]+)*@[0-9a-fA-F]{40}$ ]]; then
    echo "GitHub Action is not pinned to a full commit SHA: $action_reference" >&2
    exit 1
  fi
done < <(sed -nE 's/^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]*([^[:space:]#]+).*/\2/p' "${workflow_files[@]}")

for script in scripts/*.sh; do
  bash -n "$script"
done

if grep -H '^source = "git+' rust/Cargo.lock rust/fuzz/Cargo.lock tools/hns-header-snapshot-exporter/Cargo.lock; then
  echo "Cargo Git dependencies are not allowed in locked build inputs." >&2
  exit 1
fi

"${CARGO[@]}" metadata --locked --manifest-path rust/Cargo.toml --no-deps --format-version 1 >/dev/null
"${CARGO[@]}" metadata --locked --manifest-path rust/fuzz/Cargo.toml --no-deps --format-version 1 >/dev/null
"${CARGO[@]}" metadata --locked --manifest-path tools/hns-header-snapshot-exporter/Cargo.toml --no-deps --format-version 1 >/dev/null

echo "Supply-chain inputs are pinned and tracked-secret checks passed."
