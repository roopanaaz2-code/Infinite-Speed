# Infinite Speed — High-Velocity Offline & Local Android File Transfer

**Infinite Speed** is a modern, high-performance Android file-sharing application built to rival and surpass Zapya, Xender, and ShareIt in speed, simplicity, reliability, and privacy.

---

## 📥 Download & Install APK

You can download and install Infinite Speed directly onto any Android phone (Android 7.0 to Android 15+):

1. **Direct Repository Download:**
   - Tap [`InfiniteSpeed-v1.0.apk`](./InfiniteSpeed-v1.0.apk) or [`release/InfiniteSpeed.apk`](./release/InfiniteSpeed.apk) in this repository and tap **Download Raw**.
2. **From GitHub Releases:**
   - Go to the **[Releases](../../releases)** section on GitHub and download `InfiniteSpeed-v1.0.apk`.
3. **From GitHub Actions:**
   - Go to the **Actions** tab, tap the latest workflow run, and download the `InfiniteSpeed-APK` artifact.

### How to Install on Android
- Once downloaded, open the `.apk` file from your Downloads folder.
- If prompted, tap **Settings** and allow **"Install unknown apps"** for your browser or file manager.
- Tap **Install** and open Infinite Speed!

---

## ⚡ Key Highlights & Architecture

- **Ultra-Fast Offline Sharing:** Achieves sustained maximum local throughput (up to hardware Wi-Fi PHY limits) without consuming mobile data, internet quotas, or routing through external servers.
- **Dual Peer Discovery:**
  - **Android NSD (mDNS / DNS-SD):** Automatic discovery on standard multicast-enabled Wi-Fi networks.
  - **UDP Broadcast Beacon:** Active beacon broadcast on UDP port `8988` to `255.255.255.255` guaranteeing instant discovery even on networks where routers block or filter mDNS.
- **Embedded Asynchronous Streaming Engine:**
  - Lightweight HTTP/TCP transfer socket server on port `8990`.
  - Configurable 128KB bounded chunk streaming that avoids memory exhaustion even when transferring multi-gigabyte files.
- **Cryptographic Security & Data Integrity:**
  - **SHA-256 Stream Verification:** Hardware-accelerated hashing calculated during streaming and verified against the sender's manifest before marking complete.
  - **Strict Path Traversal Protection:** Received filenames are sanitized against directory traversal attacks (e.g. `../`).
  - **Session PIN & QR Pairing:** One-time 6-digit numeric pairing PIN or authenticated QR token.
- **Intelligent File Manager:**
  - Deep categorization for Photos, Videos, Audio, Documents, APKs, and Archives.
  - MediaStore scanning with thumbnail previews and multi-select cart.
  - Installed application APK sharing without silent installs or security compromises.
- **Zero-Install Web / PC Share:**
  - Any desktop PC, Mac, Linux machine, iPhone, or iPad on the same Wi-Fi network can open `http://<ip>:8990/web` in their browser to download shared files without installing anything.
- **Room SQLite Local Persistence:**
  - Complete history of sent and received transfers with speeds, file sizes, peer names, and timestamps.
- **Background Foreground Service:**
  - `TransferForegroundService` with notification progress bar and ongoing transfer speed updates.

---

## 🚀 Getting Started

### Requirements
- Android SDK 24+ (Android 7.0 through Android 15+)
- Kotlin 2.2+
- Jetpack Compose with Material 3

### Building the Project
```bash
gradle assembleDebug
```

### Running Unit & Robolectric Tests
```bash
gradle testDebugUnitTest
```

---

## 🔒 Privacy Policy Summary

- **Zero Cloud Tracking:** Infinite Speed does not collect, transmit, or monetize your personal files, browsing activity, or device identifiers.
- **Direct P2P Transfer:** All file contents are streamed point-to-point directly between participating devices over local Wi-Fi or portable Wi-Fi Hotspots.
- **No Analytics SDKs:** No third-party telemetry, advertising SDKs, or tracking scripts are bundled.

---

## 🛠 Troubleshooting Common Connection Issues

1. **Devices cannot find each other on public Wi-Fi?**
   - Some public or hotel Wi-Fi routers enable "AP Client Isolation", which blocks connected phones from talking to each other.
   - **Solution:** Turn on the Portable Wi-Fi Hotspot on either phone, connect the second phone to that hotspot, and open Infinite Speed. Transfers will proceed at full Wi-Fi speed completely offline.
2. **Transfer interrupted when switching apps?**
   - Ensure Infinite Speed has notification permissions enabled so the Foreground Transfer Service can keep the socket connection alive in the background.
