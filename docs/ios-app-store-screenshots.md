# Live iOS App Store screenshots

The `Live iOS App Store Screenshots` workflow produces four truthful iPhone
screenshots without a physical iPhone. It runs only when manually dispatched
because it performs real network navigation and is intended to create a
reviewed submission artifact, not a required pull-request check.

```sh
gh workflow run ios-screenshots.yml \
  --repo Denuo-Web/hns-dane-browser \
  --ref main \
  -f reason='App Store 0.5.0 submission'
```

Download the artifact named `ios-app-store-live-screenshots-COMMIT_SHA`. It
contains:

- `01-hns-page.jpg`, captured after the shipping runtime loads
  `https://denuoweb/`
- `02-settings.jpg`, showing the corrected shipping Settings screen during that
  live HNS session
- `03-proof-details.jpg`, showing the actual proof returned for that same HNS
  navigation
- `04-webpki.jpg`, captured after the shipping runtime loads
  `https://denuoweb.com/work/hns-dane-browser`
- `manifest.json`, containing the commit, Release configuration,
  Xcode/SDK/device provenance, the security labels actually shown by the app,
  dimensions, and SHA-256 digest for every image

Each JPEG is exactly `1284 x 2778`, has no alpha channel, and fits App Store
Connect's 6.5-inch iPhone screenshot slot. The workflow creates a fresh iPhone
14 Plus simulator, with 13 Pro Max and 12 Pro Max as equivalent fallbacks.

## Truthfulness guarantees

The submission capture runs the normal app and Rust runtime in the Release
simulator configuration. It never sets `HNS_APP_STORE_SCREENSHOT_SCENE`, never
injects page HTML, and never forces a security result. The submission workflow
accepts the intended HNS screenshot only when the live response is DANE
verified, and accepts the public product page only when the app reports its
system WebPKI path. `manifest.json` records the exact visible labels.

The capture fails instead of producing an artifact when:

- the first launch does not report `Handshake headers current` within 20
  minutes (the HNS page is never captured against merely prepared or stale
  headers);
- runtime preparation for the WebPKI launch does not finish within 120 seconds;
- the HNS page does not finish within 180 seconds;
- either final address differs from its exact requested submission URL;
- the HNS page is not DANE verified or the public page is not system WebPKI;
- Proof Details does not open within 60 seconds;
- Proof Details does not identify the same `denuoweb` HNS navigation;
- the public WebPKI page does not finish within 90 seconds;
- the app presents a navigation or runtime alert;
- the Release app binary contains the Debug fixture environment key; or
- an attachment, image dimension, digest, or provenance field is missing.

`NonSubmissionFixtureScreenshotRegressionTests` remains available for offline
Debug UI regression work. Its attachments are named `UI_REGRESSION_FIXTURE_*`;
the collector and staging verifier reject them as App Store assets.

This is live simulator evidence, not the optional physical-device validation
matrix in `docs/ios-device-validation.md`.

## Review and stage the images

1. Inspect all four images at full size. Confirm that the HNS page and public
   product page rendered normally, Settings matches the shipping Android-aligned
   structure with Stateless DANE visibly rendered as a switch, Proof Details
   refers to `denuoweb`, text is not clipped, and no
   keyboard, test overlay, or alert is visible.
2. Inspect `manifest.json`. Confirm `capture.mode` is
   `live-production-runtime`, `capture.configuration` is `Release`,
   `capture.fixtureEnvironmentInjected` is `false`, and the commit is the
   intended release commit. Confirm the recorded HNS label starts with
   `DANE verified` and the public-page label reports system WebPKI, matching
   what is visibly shown.
3. Put the downloaded artifact contents below
   `build/app-store-live-screenshots/`, then run:

   ```sh
   ./scripts/stage-ios-app-store-screenshots.sh
   python3 dist/app-store/validate.py
   ```

   The staging script verifies every digest, replaces
   `dist/app-store/screenshots/en-US/` with only the four live JPEGs, and writes
   the adjacent `dist/app-store/screenshots/manifest.json` provenance gate. Do
   not copy or rename fixture images into the upload folder.
4. Upload the four approved JPEGs to App Store Connect's 6.5-inch iPhone slot
   in numerical order.

The workflow never contacts App Store Connect and does not use signing or
App Store credentials. Upload remains a deliberate manual step after review.

On a compatible Mac, run the same live capture after the unsigned iOS gate:

```sh
./scripts/run-ios-gate.sh
./scripts/generate-ios-app-store-screenshots.sh
```

Local output is written to `build/app-store-live-screenshots/`.
