#ifndef NES_CORE_H
#define NES_CORE_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define NES_SCREEN_WIDTH  256
#define NES_SCREEN_HEIGHT 240
#define NES_AUDIO_SAMPLE_RATE 44100

// NES Controller Button Masks (matches RetroPad layout)
#define NES_BTN_B      (1 << 0)
#define NES_BTN_TURBO_B (1 << 1)
#define NES_BTN_SELECT (1 << 2)
#define NES_BTN_START  (1 << 3)
#define NES_BTN_UP     (1 << 4)
#define NES_BTN_DOWN   (1 << 5)
#define NES_BTN_LEFT   (1 << 6)
#define NES_BTN_RIGHT  (1 << 7)
#define NES_BTN_A      (1 << 8)
#define NES_BTN_TURBO_A (1 << 9)

typedef struct NesEmulator NesEmulator;

NesEmulator* nes_create(void);
void nes_destroy(NesEmulator* nes);

bool nes_load_rom(NesEmulator* nes, const uint8_t* data, size_t size);
bool nes_load_rom_file(NesEmulator* nes, const char* filepath);
void nes_reset(NesEmulator* nes);

// Step 1 full video frame (~29780 CPU cycles / 262 scanlines)
// Fills frame_buffer with 256x240 RGB565 pixels (512 bytes per scanline)
// Fills audio_buffer with 16-bit signed stereo PCM samples at 44100 Hz
// Returns number of audio samples (shorts) written to audio_buffer
size_t nes_run_frame(NesEmulator* nes, uint32_t controller_mask,
                     uint16_t* frame_buffer, int16_t* audio_buffer, size_t max_audio_samples);

// Battery-backed SRAM (PRG-RAM, 8KB)
uint8_t* nes_get_sram(NesEmulator* nes, size_t* out_size);
bool nes_set_sram(NesEmulator* nes, const uint8_t* data, size_t size);

// Save states
size_t nes_serialize_size(NesEmulator* nes);
bool nes_serialize(NesEmulator* nes, uint8_t* out_data, size_t max_size);
bool nes_unserialize(NesEmulator* nes, const uint8_t* in_data, size_t size);

#ifdef __cplusplus
}
#endif

#endif // NES_CORE_H
