# Experimental native HNSR Android runbook

The `hnsrTest` Android build is a regtest-only interoperability diagnostic for
the experimental HNSR implementation in the companion HSD branch. It runs the
Handshake and HNSR protocol probe in Rust through JNI; the Android activity
owns only lifecycle observation and presentation.

This is not a simulated result and it does not route production WebView
navigation through HNSR. Browser-origin integration remains a later client
milestone.

## What the probe verifies

Against the Phase 2 HSD fixture, the native Rust client:

- completes the ordinary Handshake `version` / `verack` exchange;
- requires the experimental rendezvous service bit;
- sends the private `0xf3` HNSR envelope;
- performs `FINDNODE` / `NODES`, `SAMPLEROUTES` / `ROUTES`, and exact
  `GETROUTE` / `ROUTES` exchanges;
- parses an unnamed `HNS_NODE_V1` record and its relay tickets; and
- verifies the endpoint delegation, relay and endpoint ticket signatures,
  route signature, network magic, route key, and expiry bounds.

The Kotlin activity registers an Android default-network callback. Each
material network change invalidates the older generation and starts a fresh
native connection. A late result from an invalidated generation is never
presented as current.

## Build

From this repository, with the pinned Android SDK and NDK configured:

```sh
cd android
./gradlew :app:assembleHnsrTest
```

The APK is written to:

```text
android/app/build/outputs/apk/hnsrTest/app-hnsrTest.apk
```

The focused native checks are:

```sh
cd rust
cargo test -p hns-p2p hnsr --all-features
cargo check -p android-ffi
```

## Run with the Phase 2 HSD fixture

In the companion HSD checkout, keep the passing fixture open:

```sh
HNSR_PHASE2_HOLD_OPEN=1 NODE_BACKEND=js \
  node scripts/run-hnsr-phase2-trial.js
```

The runner prints its Android bootstrap, for example
`127.0.0.1:30568`. Map that exact P2P port through ADB, install the build, and
launch the diagnostic with the same endpoint:

```sh
adb reverse tcp:30568 tcp:30568
adb install -r android/app/build/outputs/apk/hnsrTest/app-hnsrTest.apk
adb shell am start \
  -n com.denuoweb.hnsdane.hnsrtest/com.denuoweb.hnsdane.ui.HnsrDiagnosticsActivity \
  --es bootstrap 127.0.0.1:30568
```

Use the port printed by the current fixture, not the example port above. A
passing screen identifies native Rust as the transport and lists the observed
HNSR operations, exact and sampled route counts, sequence, ticket count, and
signature checks.

To exercise lifecycle handling, disable and restore the active Wi-Fi or mobile
network while the activity is visible. The screen must advance the Android
network generation and show another successful native probe after restoration.
Stop the held HSD fixture with `Ctrl-C`, then remove the matching ADB reverse:

```sh
adb reverse --remove tcp:30568
```

## Safety boundary

The probe accepts only `regtest`, uses bounded framing and timeouts, rejects
unknown or malformed authentication chains, and ships only in the dedicated
debug-derived `hnsrTest` application ID. The ordinary debug and release entry
points do not launch it.
