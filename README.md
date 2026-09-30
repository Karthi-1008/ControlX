# ControlX — GBA TV Player & Pocket Pad Controller

A purpose-built dual-application suite tailored specifically for low-specification Android TVs (such as TCL Smart TVs with MediaTek MT5867, ARM Cortex-A55, Mali-G31 GPU, and limited ~350MB free RAM).

---

## 🎮 Applications Overview

This monorepo contains two complementary Android applications:

| Application | Target | Purpose | Key Tech |
|---|---|---|---|
| **TV Player (GBA & NES)** (`gba-tv-player`) | Android TV (API 26–30+) | High-performance, lightweight GBA & NES emulator | Native mGBA Core & Custom C++ NES Engine (JNI), OpenGL ES 2.0, Low-latency AudioTrack, Android TV Leanback UI |
| **Pocket Pad** (`pocket-pad-controller`) | Android Phone (API 28+) | Low-latency physical Bluetooth HID Gamepad | `android.bluetooth.BluetoothHidDevice`, zero-network Bluetooth HID, Multi-touch virtual layout |

---

## 🚀 Key Features

### 📺 App A — TV Player (GBA & NES)
- **Dual Console Emulation**:
  - **Nintendo Game Boy Advance (GBA)**: Powered by native mGBA core (240x160, 3:2 aspect ratio, 59.7 FPS, 32,768 Hz stereo).
  - **Nintendo Entertainment System (NES)**: Powered by custom zero-overhead native C++ NES core (256x240, 4:3 aspect ratio, 60.1 FPS, 44,100 Hz stereo). Supports standard iNES format across major mappers: NROM (0), MMC1 (1), UxROM (2), CNROM (3), MMC3 (4), and AxROM (7).
- **Auto Console Detection**: Automatically identifies GBA vs. NES cartridges from file extension (`.gba`, `.agb`, `.bin`, `.nes`) or compressed `.zip` archives.
- **Hardware-Accelerated OpenGL ES 2.0 Surface**: Dynamic viewport scaling (3:2 for GBA, 4:3 for NES) with toggleable Nearest-Neighbor and Bilinear filtering.
- **Ultra-Low-Latency Audio**: Native `AudioTrack` low-latency mode (44.1 kHz stereo for NES, 32.8 kHz stereo for GBA).
- **Sub-150MB Resident Memory**: Native engines execute outside Java VM heap, staying well within strict 192MB TV heap caps.
- **Seamless ROM Loading**: Reads `.gba`, `.agb`, `.bin`, `.nes`, and `.zip` archives directly without manual disk extraction.
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