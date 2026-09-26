# ControlX — GBA TV Player & Pocket Pad Controller

A purpose-built dual-application suite tailored specifically for low-specification Android TVs (such as TCL Smart TVs with MediaTek MT5867, ARM Cortex-A55, Mali-G31 GPU, and limited ~350MB free RAM).

---

## 🎮 Applications Overview

This monorepo contains two complementary Android applications:

| Application | Target | Purpose | Key Tech |
|---|---|---|---|
| **GBA TV Player** (`gba-tv-player`) | Android TV (API 26–30+) | High-performance, lightweight GBA emulator | mGBA Libretro Core (JNI), OpenGL ES 2.0, Low-latency AudioTrack, Android TV Leanback UI |
| **Pocket Pad** (`pocket-pad-controller`) | Android Phone (API 28+) | Low-latency physical Bluetooth HID Gamepad | `android.bluetooth.BluetoothHidDevice`, zero-network Bluetooth HID, Multi-touch virtual layout |

---

## 🚀 Key Features

### 📺 App A — GBA TV Player
- **Native mGBA Core**: High accuracy and efficiency, compiled via Android NDK for `armeabi-v7a` and `arm64-v8a`.
- **Locked 59.7 FPS Performance**: Hardware-accelerated OpenGL ES 2.0 rendering surface with toggleable Nearest-Neighbor and Bilinear scaling.
- **Ultra-Low-Latency Audio**: Native `AudioTrack` low-latency mode (≤ 50ms latency at 32,768 Hz stereo PCM).
- **Sub-150MB Resident Memory**: Native core runs outside Java VM heap, respecting the TV's strict 192MB heap ceiling.
- **Seamless ROM Loading**: Reads `.gba`, `.agb`, `.bin`, and `.zip` archives directly without manual disk extraction.
- **Save States & Battery Saves**: 4 save state slots with instant save/load, plus automatic SRAM `.sav` persistence.
- **Android TV Remote & Gamepad Support**: Navigate menus with your standard TV remote; play games using Pocket Pad or any Bluetooth gamepad.
- **In-App Button Remapping**: Rebind any controller key to your preference.
- **Auto-Detection**: Recognizes `PocketPad-` controllers and displays an on-screen toast when paired.

### 📱 App B — Pocket Pad
- **Genuine Bluetooth HID Gamepad**: Registers your phone as a standard Bluetooth HID Gamepad directly in the TV OS. No receiver app or Wi-Fi required!
- **Zero Network Lag**: Operates over Bluetooth HID rather than Wi-Fi sockets, ensuring minimal latency and freedom from network congestion.
- **Authentic GBA Layout**: Responsive multi-touch D-pad (with 8-way directional sectors and diagonals), Action buttons A and B, L and R shoulder triggers, Select and Start.
- **One-Tap TV Pairing & Reconnect**: Simple discovery helper and persistent one-tap reconnect to your TV.
- **Tactile Haptics**: Optional responsive vibration feedback.
- **Battery Saver**: Automatically dims screen brightness during inactivity while maintaining Bluetooth connection.

---

## 📁 Repository Structure

```
/ControlX
├── .github/workflows/build.yml     # Automated CI to build both APKs
├── gba-tv-player/
│   ├── app/                        # Android TV Leanback frontend & emulator UI
│   └── native-mgba/                # JNI wrapper and mGBA libretro core
├── pocket-pad-controller/
│   └── app/                        # Phone Bluetooth HID gamepad app
├── docs/
│   └── PAIRING_GUIDE.md            # End-user setup and pairing walkthrough
├── settings.gradle.kts
├── build.gradle.kts
├── gradlew
└── README.md
```

---

## 🛠️ Building the Apps

### Automated GitHub Actions CI
A GitHub Actions workflow is included at `.github/workflows/build.yml`. On every push to `main` (or via manual trigger), GitHub Actions builds debug APKs for both modules and publishes them as downloadable artifacts:
- `gba-tv-player-debug-apk`
- `pocket-pad-controller-debug-apk`

### Local Build (Command Line)
To build both APKs locally:
```bash
# Build Pocket Pad phone controller APK
./gradlew :pocket-pad-controller:app:assembleDebug

# Build GBA TV Player emulator APK (requires Android NDK)
./gradlew :gba-tv-player:app:assembleDebug
```

Built APKs are located at:
- `pocket-pad-controller/app/build/outputs/apk/debug/app-debug.apk`
- `gba-tv-player/app/build/outputs/apk/debug/app-debug.apk`

---

## 📖 Pairing & Quickstart Guide
For step-by-step pairing instructions, see [PAIRING_GUIDE.md](docs/PAIRING_GUIDE.md).

---

## ⚖️ License & Compliance
- **Project Code**: MIT License.
- **mGBA Core**: Mozilla Public License 2.0 (MPL-2.0).
- This project does not bundle copyrighted ROMs or BIOS files. Users supply their own legally-owned backups.