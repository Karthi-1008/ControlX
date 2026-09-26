# Pocket Pad & GBA TV Player — Pairing & Setup Guide

This guide explains how to pair your phone running **Pocket Pad** as a low-latency Bluetooth HID controller with your Android TV running **GBA TV Player**.

---

## 1. Initial Bluetooth Pairing (One-time Setup)

Pocket Pad turns your Android phone into an authentic **Bluetooth HID Gamepad** recognized natively by your TV's operating system. No Wi-Fi or server software is needed.

### Step 1: Open Pocket Pad on your phone
1. Launch **Pocket Pad** on your phone.
2. When prompted, grant the necessary Bluetooth permissions (`Bluetooth`, `Nearby Devices / Bluetooth Connect` on Android 12+).
3. Tap **Connect to TV** in the top bar.
4. In the dialog, tap **Make Discoverable (300s)**.
   - Your phone is now discoverable as **`PocketPad-Controller`**.

### Step 2: Pair on your Android TV
1. Turn on your TV and open **Settings** using your TV remote.
2. Navigate to **Remotes & Accessories** (or **Bluetooth Devices**).
3. Select **Add accessory** (or **Pair accessory**).
4. Look for **PocketPad-Controller** in the list of available devices and select it.
5. Confirm the pairing prompt on both the TV and your phone.
6. The status dot in Pocket Pad will turn **Green** and display **`Connected to [Your TV Name]`**.

---

## 2. Using GBA TV Player on Android TV

1. Launch **GBA TV Player** from your Android TV Home screen.
2. When launched, the app automatically detects the connected controller and displays:
   `PocketPad Connected: PocketPad-Controller`.
3. You can navigate the TV library using either your **TV Remote** or your **Pocket Pad** phone controller:
   - **D-Pad**: Navigate between ROMs
   - **Button A / Remote Enter**: Launch selected game
   - **Refresh**: Re-scan the ROM folder

---

## 3. Adding Games to GBA TV Player

GBA TV Player does not include copyrighted games. Place your legally-owned game ROMs in the designated folder:

1. Connect a USB flash drive or use a file manager app (like *FX File Explorer* or *Send Files to TV*) to copy files to:
   ```
   /storage/emulated/0/GBA_ROMS/
   ```
2. Supported formats:
   - Uncompressed: `.gba`, `.agb`, `.bin`
   - Compressed: `.zip` (no manual extraction required)
3. Return to **GBA TV Player** and tap **Refresh** (or restart the app). Your games will appear with cartridge cards, titles, and sizes.

---

## 4. In-Game Controls & Features

### Pocket Pad Layout
| Control | GBA Function |
|---|---|
| **D-Pad (8-way)** | Movement (Supports diagonals and slide gestures) |
| **A Button (Red)** | Action A |
| **B Button (Purple)** | Action B |
| **L Shoulder** | GBA L Trigger |
| **R Shoulder** | GBA R Trigger |
| **Select** | Select |
| **Start** | Start |

### In-Game Pause Menu
Press the **Back** or **Menu** button on your TV Remote (or Gamepad) during gameplay to open the in-game menu:
- **Resume**: Continue playing
- **Save State**: Save instant snapshot to Slot 1, 2, 3, or 4
- **Load State**: Restore snapshot from Slot 1, 2, 3, or 4
- **Fast Forward**: Toggle 1x / 2x speed
- **Filter**: Switch between **Nearest-Neighbor** (crisp retro pixel art) and **Bilinear** (smooth)
- **Reset**: Soft-reset game console
- **Quit to Library**: Save battery SRAM and return to game browser

### Battery Saves (.sav)
- Battery saves are automatically saved every 15 seconds during gameplay and when exiting to the library.
- Save files (`.sav`) and state files (`.state1`–`.state4`) survive TV reboots and app restarts.

---

## 5. Quick Reconnection

After the initial pairing, you do not need to repeat the discovery steps:
1. Open **Pocket Pad** on your phone.
2. Tap **Quick Reconnect**.
3. Pocket Pad immediately re-establishes the direct Bluetooth HID connection to your TV.

---

## 6. Button Remapping

If you use a third-party Bluetooth/USB gamepad:
1. Open **GBA TV Player** on your TV.
2. Select **Remap Controls** from the top bar.
3. Choose the button you wish to customize and press the corresponding button on your controller.
4. Settings are saved permanently in app preferences.
