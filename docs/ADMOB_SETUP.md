# AdMob setup — Infinite Speed

The app uses Google's official Android Mobile Ads SDK and UMP consent SDK. The checked-in build defaults now point to the owner's real AdMob app and banner unit:

- App ID: `ca-app-pub-9824029664771146~9989454437`
- Banner unit: `ca-app-pub-9824029664771146/9390688637`

These IDs were created in the owner's AdMob account for **Infinite Speed**. Do not share account passwords, payment information, signing keys, or API keys.

## Placement and transfer safety

A single real AdMob banner appears only on the Home screen, below the home content. It is not loaded on file selection, QR scanning, device discovery, pairing, active transfer, receiving, web sharing, or transfer completion screens. It does not receive or inspect file contents and adds no storage or media permissions.

UMP consent information is refreshed on app launch. A required consent form is shown before ads are requested. If ads cannot be requested, the app continues without a banner.

## AdMob readiness

The app was added to AdMob as an Android app that is not yet listed in a supported public app store. AdMob currently marks it **Requires review**. The new banner unit can take up to about one hour to begin serving. Normal serving and revenue still require a supported public store listing, linking that listing to this AdMob app, completion of privacy/Data Safety requirements, account verification, and Google's app-readiness review.

## Release checklist

1. Build using the real IDs already configured in `app/build.gradle.kts`.
2. Keep privacy messaging current in AdMob.
3. Install the release build and confirm that the banner never interrupts or overlays a transfer.
4. Never click your own live ads. Use Google's test IDs or a registered test device for any click/interactions testing.
5. Publish the app in a supported app store and link the public listing to AdMob. A GitHub APK alone does not complete AdMob app-readiness review.
6. Complete privacy policy, store Data Safety disclosures, and any applicable `app-ads.txt` configuration.
