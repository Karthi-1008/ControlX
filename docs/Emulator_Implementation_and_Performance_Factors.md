# How These Emulators Actually Work — Implementation Details & Performance Factors

*A technical companion to the TV config report — this explains **why** certain consoles run well on your TV and others don't, based on how each emulator is actually built.*

---

## 1. The Core Concept: Three Ways to Emulate a CPU

Every emulator has to somehow run code written for a completely different processor. There are three main approaches, in increasing order of speed (and complexity):

| Method | How it works | Speed | Used for |
|---|---|---|---|
| **Pure Interpreter** | Reads the original console's instructions one at a time, translates and executes each one on the fly, forever, every single frame | Slowest | Fallback mode when other methods fail or aren't ported to your CPU architecture |
| **Cached Interpreter** | Same as above, but remembers translations of code blocks it's already interpreted, so it doesn't redo the same work repeatedly | Medium | A safer middle-ground on some emulators |
| **Dynamic Recompiler (JIT / "Dynarec")** | Translates blocks of the original CPU's instructions into your device's native machine code *once*, then runs that native code directly — essentially rewriting the game's program into ARM instructions before playing it | Fastest | Nearly all performance-focused N64, PS1, PSP emulators |

**Why this matters for your TV specifically:** dynamic recompilation is what makes emulation fast — but it only pays off if the recompiler has been written and optimized for your exact CPU type (ARMv8 in your case). If a dynarec isn't well-tuned for weak cores like your Cortex-A55, or falls back to an interpreter for compatibility, performance drops sharply. This is a big part of why some games run fine and others don't, even on the same emulator.

---

## 2. Per-System Implementation Breakdown

### NES / SNES / Genesis / Game Boy / GBA (your "smooth" tier)
- **Implementation type:** Mostly direct interpretation, sometimes with light recompilation
- **Why it's easy:** These original consoles ran at 1.5–16 MHz. Your TV's weakest core alone runs at 1500 MHz — even a "slow" interpreter is emulating a chip roughly 100–1000× slower than your hardware
- **Cores:** FCEUmm/Nestopia (NES), Snes9x (SNES), Genesis Plus GX (Genesis), Gambatte (GB/GBC), mGBA (GBA)
- **Bottom line:** No advanced techniques even needed — brute-force interpretation is already fast enough

### PlayStation 1 / PSX (your "playable" tier)
- **Implementation type:** Dynamic recompiler, MIPS → ARM
- **Core:** PCSX ReARMed — the name literally comes from "ReARMed," because it was built and hand-optimized specifically for ARM chips (originally for the Pandora handheld console)
- **Why this core specifically:** It includes an ARM-native GPU renderer (NEON GPU by Exophase) that avoids relying on your GPU driver much, which matters since your Mali-G31 + weak CPU combo doesn't handle enhanced/upscaled rendering well
- **Why some games still lag:** Games that push more polygons or CD audio streaming (racers, fast 3D platformers) demand more of both the dynarec and the CPU's memory bandwidth than your ~350MB free RAM and Cortex-A55 cores can sustain

### Nintendo 64 (your "avoid" tier)
- **Implementation type:** Dynamic recompiler *or* cached/pure interpreter (selectable), plus a **separate plugin system** for graphics and audio
- **Core:** Mupen64Plus-Next (the actively maintained successor to the original Mupen64Plus-libretro)
- **The plugin architecture is the key issue:** N64 emulation doesn't just emulate one chip — it emulates the main CPU (R4300), *and* a separate co-processor called the RSP (Reality Signal Processor) that handles graphics/audio math. The RSP can be run two ways:
  - **HLE (High-Level Emulation):** Fakes the RSP's *results* using shortcuts — faster, less accurate
  - **LLE (Low-Level Emulation):** Actually simulates the RSP chip instruction-by-instruction — much more accurate, but far heavier
- **Why it struggles on your TV:** Even in the fast HLE mode, you're running a dynamic recompiler for the main CPU *and* a graphics plugin (like GLideN64) simultaneously, competing for the same weak cores and the same ~350MB of free RAM. There's no "light mode" equivalent to what NES/SNES have.

### PSP (your "worst" tier)
- **Implementation type:** Dynamic recompiler (JIT) with a modern, actively-optimized GPU emulation layer
- **Core:** PPSSPP (standalone, not a RetroArch core in most setups)
- **Why it's the heaviest:** The PSP itself contained a CPU that ran at 222–333 MHz *and* a dedicated 3D graphics chip. Emulating the graphics chip essentially requires re-implementing 3D rendering logic on your GPU driver (Mali-G31) at a level of complexity your GPU's limited feature set (no BC texture compression, no sparse residency, no multiview, per the config report) wasn't built to serve efficiently. Combined with the 192MB Java heap ceiling on your TV, PPSSPP simply cannot get the memory or GPU headroom it needs.

### Sega Saturn (also "avoid" tier)
- **Implementation type:** Interpreter-based (dynamic recompilation for Saturn is notoriously unreliable even today)
- **Core:** Beetle Saturn (Mednafen core)
- **Why it's uniquely hard:** The real Saturn hardware used **two CPUs running in parallel** (dual Hitachi SH-2 processors) plus multiple additional custom chips for graphics. Accurately emulating two CPUs syncing with each other in real time is fundamentally more CPU-expensive than emulating one — this is a hardware design problem, not something better coding can fully fix. Even flagship phone chips handle Saturn inconsistently.

---

## 3. Other Factors That Affect Real-World Performance

These are TV-specific factors (from your config report) that interact with the implementation details above:

| Factor | Your TV's value | Effect |
|---|---|---|
| **Free RAM** | ~350MB | Caps how large a game's data + emulator buffers can be held in memory at once — this is why RAM-hungry systems (PSP, Saturn, N64) fail before even hitting a CPU limit |
| **Java VM Heap Size** | 192MB | Any single app (like RetroArch or PPSSPP) is hard-capped here for its managed memory — a ceiling independent of total system RAM |
| **CPU clock range** | 750–1500 MHz | Determines how many recompiled instructions per second your device can actually execute — directly limits dynarec throughput |
| **CPU core type** | Cortex-A55 (efficiency core) | Optimized for low power draw, not peak single-thread performance — emulation is almost always single-thread-bottlenecked, so this matters more than having 4 cores |
| **GPU feature gaps** | No BC compression, no sparse residency, no multiview, limited depth features | Modern/enhanced rendering paths in emulators (upscaling, texture packs, advanced shaders) may not run at all, or fall back to slow paths |
| **32-bit CPU mode** | ARMv8 running in 32-bit compatibility mode | Some emulator builds specifically optimized for 64-bit ARM (AArch64) won't get their full speed benefit here |

---

## 4. Summary Table: Implementation vs. Outcome

| System | Technique | Plugin/Chip Complexity | RAM Pressure | Result on Your TV |
|---|---|---|---|---|
| NES/SNES/Genesis/GB/GBA | Interpreter | None | Very low | ✅ Full speed |
| PS1 | Dynarec (ARM-optimized) | Low (single CPU) | Low-medium | ⚠️ Good for simple/turn-based games |
| N64 | Dynarec + separate RSP plugin | High (2 subsystems) | Medium-high | ❌ Inconsistent, mostly poor |
| Saturn | Interpreter (dynarec unreliable) | Very high (dual CPU) | High | ❌ Poor |
| PSP | Dynarec + full 3D GPU emulation | Very high | Very high | ❌ Not viable |

---

## 5. Source Repositories (for reference)

| Emulator/Core | GitHub |
|---|---|
| RetroArch | https://github.com/libretro/RetroArch |
| FCEUmm (NES) | https://github.com/libretro/libretro-fceumm |
| Snes9x (SNES) | https://github.com/libretro/snes9x |
| Genesis Plus GX | https://github.com/libretro/Genesis-Plus-GX |
| Gambatte (GB/GBC) | https://github.com/libretro/gambatte-libretro |
| mGBA (GBA) | https://github.com/libretro/mgba |
| PCSX ReARMed (PS1) | https://github.com/libretro/pcsx_rearmed |
| Mupen64Plus-Next (N64) | https://github.com/libretro/mupen64plus-libretro-nx |
| Beetle Saturn | https://github.com/libretro/beetle-saturn-libretro |
| PPSSPP (PSP) | https://github.com/hrydgard/ppsspp |
| M64Plus FZ (your original app) | https://github.com/paulscode/MupenPlus64AE |

---

## Bottom line

The pattern across every system is the same: **your TV's bottleneck is CPU throughput and free RAM, not raw GPU capability.** Any emulator whose implementation adds a second CPU-heavy component — a graphics co-processor (N64), a second real CPU (Saturn), or full 3D pipeline emulation (PSP) — pushes past what your Cortex-A55 cores and ~350MB of free memory can sustain. Systems where a single, well-optimized interpreter or dynarec is enough (NES through PS1) are where your TV performs well.
