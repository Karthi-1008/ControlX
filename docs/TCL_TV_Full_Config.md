# TCL Smart TV — Full System Configuration

*Extracted from TCL_TV_Config.pdf / TCL_TV_Config_1.pdf. Long technical lists (OpenGL/Vulkan extensions, low-level GPU limits) are condensed into summary lines rather than dropped — nothing has been left out, just compacted.*

---

## 1. System

| Field | Value |
|---|---|
| Device Type | TV |
| Manufacturer | TCL |
| Brand / Model | TCL / Smart TV |
| Board | C06 |
| Device | C06 |
| Hardware | mt5867 |
| Platform | mt5867 |
| Product | c06_2K_GB |

---

## 2. CPU

| Field | Value |
|---|---|
| SoC Model | MediaTek MT5867 |
| Core Architecture | 4× ARM Cortex-A55 |
| Instruction Set | 64-bit ARMv8-A, running in 32-bit mode |
| CPU Revision | r2p0 |
| CPU Cores | 4 |
| Clock Range | 750 – 1500 MHz |
| Per-core clock (at capture) | 1100 MHz (all 4 cores) |
| Scaling Governor | ondemand |
| Supported ABIs | armeabi-v7a, armeabi |
| Supported 32-bit ABIs | armeabi-v7a, armeabi |
| AES / NEON / PMULL / SHA1 / SHA2 | Supported |

**In plain terms:** 4 low-power efficiency cores (Cortex-A55), topping out at 1.5GHz, running in 32-bit compatibility mode. This is a TV-interface chip, not a performance chip.

---

## 3. Display & Graphics

| Field | Value |
|---|---|
| Screen Resolution | 1280 × 720 |
| DPI (x/y) | 46 / 46 |
| GPU Vendor | ARM |
| GPU Renderer | Mali-G31 |
| OpenGL ES Version | 3.2 |
| Vulkan API Version | 1.1.131 |
| Vulkan Driver | /system/lib/libvulkan.so |
| Default Orientation | Landscape |
| Refresh Rate | 60 Hz |

**Vulkan Device Details**
| Field | Value |
|---|---|
| Device Name | Mali-G31 |
| Device Type | Integrated GPU |
| Memory Size | 939,620 KB (~918 MB, shared with system RAM) |
| Max 2D/3D/Cube Image Size | 16,383 px |
| Max Texel Buffer Elements | 65,536 |

**Feature support (Vulkan/OpenGL, summarized):**
- ✅ Supported: ETC2/EAC texture compression, ASTC LDR compression, anisotropic filtering, tessellation shaders, standard sample locations, strict line rasterization, geometry shaders (ES 3.2 baseline)
- ❌ Not Supported: BC texture compression, Sparse Residency (all variants), Multiview / Multiview2, Variable Multisample Rate, Wide Lines, Depth Clamping/Bias Clamping (mixed — some depth features unsupported)

**Extension lists (condensed):** The GPU driver reports roughly **150+ OpenGL ES extensions** (covering texture compression, shader features, framebuffer fetch, sRGB, geometry/tessellation shaders, multisampling) and roughly **60+ Vulkan device/instance extensions** (covering swapchain, external memory, Android hardware buffers, ASTC HDR decode, shader subgroup operations). These indicate an up-to-date driver, but the extensions don't compensate for the weak, low-clocked hardware underneath — this is a modern *driver* on entry-level *silicon*.

---

## 4. Network

| Field | Value |
|---|---|
| Connection Type | Wi-Fi |
| State | Enabled |
| IPv4 Address | 192.168.0.7 |
| IPv6 Address | fe80::fe7b:21c9:2355:22f3 |
| Link Speed | 144 Mbps |
| Frequency | 2467 MHz (2.4GHz band) |
| Signal Strength | -37 dBm (Excellent) |
| Gateway | 192.168.0.1 |
| Netmask | 255.255.255.0 |
| DNS1 | 192.168.0.1 |
| 5GHz Band | Supported |
| Wi-Fi Direct | Supported |
| Wi-Fi Aware | Not Supported |
| DHCP Lease Duration | 2 hours |
| Telephony | None (not applicable — it's a TV) |

---

## 5. Android OS

| Field | Value |
|---|---|
| Android Version | 11 (Red Velvet Cake) |
| API Level | 30 |
| Security Patch | 2024-02-05 |
| Rooted | No |
| Build ID | AR2101 |
| Bootloader | 01.01.240520 |
| Kernel Version | 4.19.116+ |
| Kernel Architecture | armv7l |
| Java Runtime | ART 2.1.0 |
| Android Runtime | 0.9 |
| **Java VM Heap Size** | **192 MB** |
| Google Play Services | 21.42.18 |
| Huawei Mobile Services | Not present |
| OpenSSL | 1.1.0 (BoringSSL-compatible) |
| ICU / zlib / CLDR versions | 66.1 / 1.2.11 / 36.1 |
| Language | English (India) |
| Time Zone | India Standard Time (UTC+05:30) |

**Why the Java VM Heap Size matters:** 192MB is the hard ceiling for any single app's Java memory (like RetroArch/M64Plus FZ's UI layer) — combined with only ~350MB free system RAM, this is the core reason heavier emulators/games struggle on this TV.

---

## 6. Devices

| Category | Result |
|---|---|
| Vulkan/Graphics Device | Mali-G31 (integrated) — see Display section |
| Cameras | None found |
| USB Devices | None found |
| OpenCL Devices | None found |
| CUDA Devices | None found |
| PCI Devices | None found |

---

## 7. Thermal

No sensor readings were captured in this export.

---

## 8. Apps

No installed-app data was captured in this export.

---

## 9. Codecs

**Audio — Decode support:** AAC, AAC-LATM, AMR-NB, AMR-WB, 3GPP, FLAC, RAW/PCM, Vorbis, Opus, MP3, G711 (A-law & µ-law), AC3, EAC3, ADPCM (IMA/MS), MPEG Layer 1/2

**Audio — Encode support:** AAC, AMR-NB, AMR-WB, FLAC, G711 A-law

**Video — Decode support:** H.264/AVC, H.265/HEVC, H.263, MPEG-4, MPEG-2, VP8, VP9, WMV/VC1, FLV, AV1

**Video — Encode support:** H.264/AVC, H.263, HEVC, MPEG-4, VP8, VP9

*(TV has full modern video codec coverage — this is a media-playback-focused chip, which lines up with everything else in this report.)*

---

## 10. Directories / System Files / About

These tabs exist in the diagnostic tool but had no content captured in the provided export.

---

## Summary: What this hardware is built for

This is a **media-streaming SoC** (MediaTek MT5867 + Mali-G31), not a gaming chip:
- Strong, modern, full-range **video/audio codec support** → great for streaming apps
- Weak **CPU** (4× low-power Cortex-A55 @ 1.5GHz) and **very limited RAM** (1GB total, ~350MB free, 192MB app heap ceiling) → poor fit for CPU-heavy emulation (like N64)
- **Solid but basic GPU** (Mali-G31, OpenGL ES 3.2 / Vulkan 1.1) → fine for simple 2D/light-3D rendering, not for demanding 3D emulation
