# Build and Supply-Chain Audit

Last audited: 2026-07-12

## Enforced Gates

- GitHub Actions runs the shipping Rust workspace, fuzz workspace, header-snapshot exporter, cargo-deny policy, Android unit tests, lint, debug assembly, and unsigned release bundle build. Workflow permissions are read-only, release secrets are not provided, every non-local `uses:` reference must be pinned to a full commit SHA, checkout credentials are not persisted, and concurrent runs on the same ref are cancelled.
- Dependabot watches GitHub Actions, Gradle, and all three Cargo lockfile roots weekly.
- Rust uses toolchain `1.92.0`; build, clippy, test, metadata, Android cross-compile, and cargo-deny commands use committed lockfiles with `--locked`. No Cargo lockfile contains a Git dependency, and registry packages carry Cargo checksums.
- cargo-deny covers all three manifests. The fuzz and exporter packages now declare the repository license. `NCSA` is allowed specifically because `libfuzzer-sys` combines its MIT/Apache-2.0 code with LLVM libFuzzer code under the University of Illinois/NCSA license.
- Gradle 9.6.1 has an official distribution checksum in `gradle-wrapper.properties`; the checked-in wrapper JAR is independently compared with the official wrapper-JAR SHA-256. Android dependency locking runs in strict mode, and Gradle verification metadata pins SHA-256 hashes for resolved artifacts and metadata.
- `scripts/verify-supply-chain.sh` checks the exact wrapper distribution URL and hashes, required lock/verification files, Cargo lock consistency and absence of Git sources, shell syntax, immutable Action references, tracked secret-bearing filenames, and high-confidence secret patterns. Root-invoked Rust scripts explicitly select toolchain `1.92.0` instead of relying on rustup to discover a toolchain file beside a manifest in another directory.
- Android JNI builds reject unknown profiles and unexpected cargo-ndk versions, use `--locked`, require both ABI outputs, restrict cleanup to `android/app/build`, and discover the NDK host prebuilt instead of assuming `linux-arm64`. Gradle treats the NDK location and `source.properties` as incremental task inputs and includes Rust `.txt` data files such as the ICANN TLD snapshot. CI additionally requires NDK `28.2.13676358`.
- The Play bundle gate reads every content entry through Java's verifying `JarFile`, rejects bad digests, unsigned entries, mixed signers, or a signer that does not match `HNS_DANE_BROWSER_UPLOAD_CERTIFICATE_SHA256`, then checks both required native ABIs.
- Keystores, signing properties, service-account files, environment files, private-key formats, local Android properties, and generated APK/AAB artifacts are ignored. The Play API helper keeps its bearer token out of curl's process arguments, validates URL path inputs and release status, and enforces HTTPS/TLS timeouts.

## Audit Results

- cargo-deny reports no known advisory, source, or license-policy failures for the shipping workspace, fuzz workspace, or exporter. Duplicate transitive versions and unused allow-list entries remain warnings.
- No high-confidence secret or secret-bearing filename was found among tracked files.
- Release signing and Play upload remain intentional external gates. CI builds the release variant without signing credentials and cannot publish.

## Residual Risks

- This audit pins inputs but does not establish bit-for-bit reproducible APK/AAB output. Runner images, the JDK 21 patch release selected by setup-java, Android SDK packaging, archive timestamps, and signing can still vary. A future release process should compare independently built unsigned artifacts before signing.
- Gradle verification metadata was generated from artifacts already obtained over the configured HTTPS repositories. Future checksum changes require a deliberate review; the metadata is an integrity pin, not independent provenance proof.
- cargo-deny relies on the current RustSec advisory database at check time. CI availability or an upstream advisory-database outage can affect results.
- The local JNI script validates an exact NDK revision only when `HNS_ANDROID_NDK_VERSION` is set. CI sets it; developers using APK Workbench can intentionally use another installed NDK and should set the variable for release-candidate parity.
- The upload certificate fingerprint is public configuration, but its approved value still needs an out-of-band comparison with the Play Console upload certificate before the first release.
