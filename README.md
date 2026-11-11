# OpenWrt Scanner (Android)

A rooted Android application that scans local Wi-Fi LANs and **Tailscale** networks for OpenWrt routers with active SSH (Dropbear / OpenSSH) and LuCI web services.

When an OpenWrt router is detected, the app renders a smooth **radar lock-on animation**, triggers **haptic vibration**, and **pops up a connection modal** pre-configured for OpenWrt (`root` user, password, port 22, SSH terminal launch, and LuCI web shortcut).

---

## Features

- **LAN Discovery**:
  - Automatically identifies default gateway and active subnet prefix.
  - Reads `/proc/net/arp` table for sub-second host resolution.
  - Concurrently scans port 22 and port 80 across the local `/24` subnet using a managed thread pool.
- **Tailscale Discovery (Root Enabled)**:
  - On rooted devices (Magisk / KernelSU / APatch), requests `su` permission to inspect Tailscale's protected daemon state:
    - Queries UNIX domain socket `/data/data/com.tailscale.ipn/files/tailscaled.sock` via `localapi/v0/status`.
    - Invokes `tailscale status --json` directly when the CLI binary is present.
    - Inspects routing tables (`ip route show table 52`) and CGNAT subnet `100.64.0.0/10`.
  - Concurrently probes each discovered Tailscale peer node for SSH availability.
- **OpenWrt Fingerprinting**:
  - **Dropbear SSH Check**: Connects to port 22 and reads the initial identification string (`SSH-2.0-dropbear...`). Dropbear is the default SSH server on OpenWrt.
  - **LuCI Web UI Check**: Tests port 80/443 for `sysauth` cookie or OpenWrt LuCI signatures.
  - Measures real-time latency in milliseconds.
- **Radar & Lock-On Animation**:
  - Hardware-accelerated custom Canvas radar view (`RadarScanView`).
  - Smooth 360° rotating sweep beam, concentric sonar range rings, and dynamic IP ping blips.
  - Animated glowing lock-on reticle and ripple shockwave centered on detected OpenWrt targets.
- **Haptic Vibration**:
  - Triggers a celebratory double-pulse vibration pattern upon router discovery (`VibratorManager` / `VibrationEffect` with legacy fallback).
- **Credentials & Connect Popup**:
  - Automatic popup modal upon router detection.
  - Pre-fills default OpenWrt username: `root`.
  - Password input field with show/hide toggle.
  - One-tap buttons:
    - **Connect via SSH**: Launches external SSH clients (ConnectBot, Termux, JuiceSSH) via `ssh://` intent.
    - **Verify SSH Port**: Runs an instant socket test to verify port 22 state.
    - **Open LuCI Web UI**: Launches router web dashboard (`http://<ip>/`) in browser.
    - **Copy SSH Command**: Copies `ssh root@<ip>` to system clipboard.

---

## Project Structure

```
openwrt/
├── .github/
│   └── workflows/
│       └── build-apk.yml          # GitHub Actions workflow to compile APK
├── app/
│   ├── build.gradle.kts           # App Gradle config (SDK 35, Java 17)
│   └── src/main/
│       ├── AndroidManifest.xml    # Permissions (INTERNET, VIBRATE, WIFI)
│       ├── java/com/lemonrsh/openwrt/
│       │   ├── MainActivity.kt    # Main activity with cyber dark theme
│       │   ├── model/
│       │   │   └── RouterDevice.kt# Router & scan progress data models
│       │   ├── scanner/
│       │   │   ├── LanScanner.kt  # ARP & subnet multithreaded scanner
│       │   │   ├── TailscaleScanner.kt # Root Tailscale daemon query
│       │   │   └── OpenWrtProbe.kt# Dropbear banner & LuCI HTTP probe
│       │   ├── ui/
│       │   │   ├── RadarScanView.kt # Hardware-accelerated radar view
│       │   │   └── ConnectDialog.kt # Connection & password popup
│       │   └── util/
│       │       ├── HapticHelper.kt# Vibration effect generator
│       │       └── RootHelper.kt  # Root su execution & socket reader
│       └── res/                   # Drawables, colors, styles
├── tools/
│   └── preview_server.js          # Interactive companion & preview simulator
├── build.gradle.kts               # Root Gradle project build script
├── settings.gradle.kts            # Project settings
└── gradle.properties
```

---

## Build APK

### Option A: Via GitHub Actions (Zero Local Setup)
1. Push this repository to GitHub.
2. Go to **Actions** → **Build APK** → **Run workflow**.
3. Download `openwrt-scanner-debug-apk` from the completed workflow run.

### Option B: Local Android Studio / CLI
Requirements: JDK 17, Android SDK (compileSdk 35, minSdk 26).

```bash
# Assemble debug APK
./gradlew assembleDebug

# Output APK location:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## Interactive Companion & Live Preview

A lightweight Node.js companion preview server is included to test the UI, canvas radar animation, vibration feedback, and real network socket probing directly in the browser:

```bash
node tools/preview_server.js
```

Open `http://localhost:3000` in your browser.
