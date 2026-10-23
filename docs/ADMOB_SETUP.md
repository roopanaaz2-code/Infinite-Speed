# AdMob setup — Infinite Speed

The app uses Google's official Android Mobile Ads SDK and UMP consent SDK. Development builds default to Google's test values:

- App ID: `ca-app-pub-3940256099942544~3347511713`
- Banner test unit: `ca-app-pub-3940256099942544/9214589741`

These are Google's test IDs. They are not the owner's IDs and do not generate revenue.

## Configure real IDs

Supply the owner's separate AdMob app ID and banner unit as local Gradle properties:

```bash
./gradlew assembleRelease \
  -PADMOB_APP_ID=ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY \
  -PADMOB_BANNER_ID=ca-app-pub-XXXXXXXXXXXXXXXX/ZZZZZZZZZZ
```

The properties may also be placed in an uncommitted `~/.gradle/gradle.properties` file. Do not commit signing keys, passwords, API keys, or private AdMob configuration.

## Placement and transfer safety

A single banner appears only on the home screen, below the home content. It is not loaded on file selection, QR scanning, device discovery, pairing, active transfer, receiving, web sharing, or transfer completion screens. It does not receive or inspect file contents and adds no storage or media permissions.

UMP consent information is refreshed on app launch. A required consent form is shown before ads are requested. If ads cannot be requested, the app continues without a banner.

## Release checklist

1. Keep the Google test IDs while testing. Never click test or live ads.
2. Configure the real IDs only for the intended release build.
3. Test QR pairing, LAN discovery, large files, background transfer, receiver storage, and transfer recovery with the banner enabled and disabled.
4. Confirm that the banner never interrupts or overlays a transfer.
5. Publish the app in a supported app store and link the public listing to AdMob before expecting normal serving or revenue. A GitHub APK alone does not complete AdMob app-readiness review.
6. Complete privacy messaging, Data safety disclosures, store privacy policy requirements, and any applicable app-ads.txt configuration.
