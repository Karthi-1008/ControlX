#include "nes_core.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <vector>

// NES Color Palette (64 colors in 0x00RRGGBB)
static const uint32_t NES_PALETTE[64] = {
    0x666666, 0x002A88, 0x1412A7, 0x3B00A4, 0x5C007E, 0x6E0040, 0x6C0600, 0x561D00,
    0x333500, 0x0B4800, 0x005200, 0x004F08, 0x00404D, 0x000000, 0x000000, 0x000000,
    0xADADAD, 0x155FD9, 0x4240FF, 0x7527FE, 0xA01ACC, 0xB71E7B, 0xB53120, 0x994E00,
    0x6B6D00, 0x388700, 0x0C9300, 0x008F32, 0x007C8D, 0x000000, 0x000000, 0x000000,
    0xFFFEFF, 0x64B0FF, 0x9290FF, 0xC676FF, 0xF36AFF, 0xFE6ECC, 0xFE8170, 0xEA9E22,
    0xBCBE00, 0x88D800, 0x5CE430, 0x45E082, 0x48CDDE, 0x4F4F4F, 0x000000, 0x000000,
    0xFFFEFF, 0xC0DFFF, 0xD3D2FF, 0xE8C8FF, 0xFBC2FF, 0xFEC4EA, 0xFECCC5, 0xF7D8A5,
    0xE4E594, 0xCFEF96, 0xBDF4AB, 0xB3F3CC, 0xB5EBF2, 0xB8B8B8, 0x000000, 0x000000
};

static uint16_t NES_PALETTE_RGB565[64];
static bool s_palette_inited = false;

static void init_palette_rgb565() {
    if (s_palette_inited) return;
    for (int i = 0; i < 64; i++) {
        uint32_t c = NES_PALETTE[i];
        uint32_t r = (c >> 16) & 0xFF;
        uint32_t g = (c >> 8) & 0xFF;
        uint32_t b = c & 0xFF;
        NES_PALETTE_RGB565[i] = (uint16_t)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
    }
    s_palette_inited = true;
}

// Length counter lookup table for APU
static const uint8_t LENGTH_TABLE[32] = {
    10, 254, 20, 2,  40, 4,  80, 6,  160, 8,  60, 10, 14, 12, 26, 14,
    12, 16,  24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30
};

// Noise period lookup table
static const uint16_t NOISE_PERIOD[16] = {
    4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068
};

// Pulse duty cycle waveforms (8 steps)
static const uint8_t DUTY_CYCLES[4][8] = {
    {0, 1, 0, 0, 0, 0, 0, 0}, // 12.5%
    {0, 1, 1, 0, 0, 0, 0, 0}, // 25%
    {0, 1, 1, 1, 1, 0, 0, 0}, // 50%
    {1, 0, 0, 1, 1, 1, 1, 1}  // 75% inverted 25%
};

// Triangle channel 32-step sequence
static const uint8_t TRIANGLE_SEQUENCE[32] = {
    15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
};

enum MirroringMode {
    MIRROR_HORIZONTAL,
    MIRROR_VERTICAL,
    MIRROR_SINGLE_LOW,
    MIRROR_SINGLE_HIGH,
    MIRROR_FOUR_SCREEN
};

struct NesEmulator {
    // 6502 CPU State
    uint16_t pc;
    uint8_t a, x, y, sp, p;
    uint64_t cpu_cycles;
    bool nmi_pending;
    bool irq_pending;

    // RAM
    uint8_t ram[2048];

    // Cartridge / ROM
    std::vector<uint8_t> prg_rom;
    std::vector<uint8_t> chr_rom;
    bool is_chr_ram;
    uint8_t prg_ram[8192];
    bool has_prg_ram;
    int mapper_num;
    MirroringMode mirror_mode;

    // Mapper registers
    // Mapper 1 (MMC1)
    uint8_t mmc1_shift_reg;
    uint8_t mmc1_ctrl;
    uint8_t mmc1_chr0;
    uint8_t mmc1_chr1;
    uint8_t mmc1_prg;
    // Mapper 2 (UxROM)
    uint8_t uxrom_bank;
    // Mapper 3 (CNROM)
    uint8_t cnrom_bank;
    // Mapper 4 (MMC3)
    uint8_t mmc3_bank_select;
    uint8_t mmc3_regs[8];
    uint8_t mmc3_mirroring;
    uint8_t mmc3_irq_latch;
    uint8_t mmc3_irq_counter;
    bool mmc3_irq_enabled;
    bool mmc3_irq_reload;
    // Mapper 7 (AxROM)
    uint8_t axrom_bank;

    // PPU State
    uint8_t vram[2048]; // 2 nametables
    uint8_t palette[32];
    uint8_t oam[256];
    uint8_t oam_addr;

    uint8_t ppuctrl;   // $2000
    uint8_t ppumask;   // $2001
    uint8_t ppustatus; // $2002
    uint8_t ppu_read_buf;

    // Loopy scrolling registers
    uint16_t v; // Current VRAM address (15 bits)
    uint16_t t; // Temporary VRAM address (15 bits)
    uint8_t x_scroll; // Fine X scroll (3 bits)
    bool w;     // First or second write toggle

    int scanline;
    int dot;
    bool frame_odd;
    bool sprite0_hit;

    // Framebuffer: 256x240 RGB565
    uint16_t frame_buffer[NES_SCREEN_WIDTH * NES_SCREEN_HEIGHT];

    // APU State
    // Pulse 1
    bool pulse1_enabled;
    uint8_t pulse1_duty;
    uint8_t pulse1_volume;
    bool pulse1_constant_vol;
    uint16_t pulse1_timer;
    uint16_t pulse1_timer_period;
    uint8_t pulse1_duty_pos;
    uint8_t pulse1_length;
    uint8_t pulse1_env_decay;
    uint8_t pulse1_env_counter;
    bool pulse1_env_reload;
    bool pulse1_length_halt;
    bool pulse1_sweep_enabled;
    uint8_t pulse1_sweep_period;
    uint8_t pulse1_sweep_counter;
    bool pulse1_sweep_negate;
    uint8_t pulse1_sweep_shift;
    bool pulse1_sweep_reload;

    // Pulse 2
    bool pulse2_enabled;
    uint8_t pulse2_duty;
    uint8_t pulse2_volume;
    bool pulse2_constant_vol;
    uint16_t pulse2_timer;
    uint16_t pulse2_timer_period;
    uint8_t pulse2_duty_pos;
    uint8_t pulse2_length;
    uint8_t pulse2_env_decay;
    uint8_t pulse2_env_counter;
    bool pulse2_env_reload;
    bool pulse2_length_halt;
    bool pulse2_sweep_enabled;
    uint8_t pulse2_sweep_period;
    uint8_t pulse2_sweep_counter;
    bool pulse2_sweep_negate;
    uint8_t pulse2_sweep_shift;
    bool pulse2_sweep_reload;

    // Triangle
    bool triangle_enabled;
    uint16_t triangle_timer;
    uint16_t triangle_timer_period;
    uint8_t triangle_step;
    uint8_t triangle_length;
    uint8_t triangle_linear_counter;
    uint8_t triangle_linear_reload_val;
    bool triangle_linear_reload_flag;
    bool triangle_length_halt;

    // Noise
    bool noise_enabled;
    uint16_t noise_shift_reg;
    bool noise_mode;
    uint16_t noise_timer;
    uint16_t noise_timer_period;
    uint8_t noise_length;
    uint8_t noise_volume;
    bool noise_constant_vol;
    uint8_t noise_env_decay;
    uint8_t noise_env_counter;
    bool noise_env_reload;
    bool noise_length_halt;

    // Frame counter
    uint8_t frame_counter_mode; // 0: 4-step, 1: 5-step
    bool frame_counter_irq_disable;
    uint32_t frame_counter_cycle;

    // APU audio sample generator
    uint32_t sample_accumulator;
    std::vector<int16_t> audio_samples;

    // Controller
    uint32_t controller_state;
    uint8_t controller_shift;
    bool controller_strobe;
    uint32_t turbo_counter;

    // Forward declarations of memory access
    uint8_t cpu_read(uint16_t addr);
    void cpu_write(uint16_t addr, uint8_t val);
    uint8_t ppu_read(uint16_t addr);
    void ppu_write(uint16_t addr, uint8_t val);
    void step_ppu();
    void render_pixel();
    void step_apu();
    void clock_apu_quarter_frame();
    void clock_apu_half_frame();
    void generate_audio_sample();
    void mmc3_clock_scanline();
};

// Mirroring helper
static uint16_t mirror_nametable_addr(uint16_t addr, MirroringMode mode) {
    addr = (addr - 0x2000) % 0x1000;
    int table = addr / 0x400;
    int offset = addr % 0x400;
    switch (mode) {
        case MIRROR_VERTICAL:
            return ((table & 1) * 0x400) + offset;
        case MIRROR_HORIZONTAL:
            return (((table >> 1) & 1) * 0x400) + offset;
        case MIRROR_SINGLE_LOW:
            return offset;
        case MIRROR_SINGLE_HIGH:
            return 0x400 + offset;
        default:
            return (table * 0x400) + offset;
    }
}

// PRG ROM mapping
static uint32_t map_prg_addr(NesEmulator* nes, uint16_t addr) {
    uint32_t prg_size = (uint32_t)nes->prg_rom.size();
    if (prg_size == 0) return 0;

    switch (nes->mapper_num) {
        case 0: { // NROM
            uint32_t offset = addr - 0x8000;
            return offset % prg_size;
        }
        case 1: { // MMC1
            int mode = (nes->mmc1_ctrl >> 2) & 0x03;
            uint32_t bank = nes->mmc1_prg & 0x0F;
            uint32_t num_16k_banks = prg_size / 0x4000;
            if (num_16k_banks == 0) num_16k_banks = 1;

            if (mode == 0 || mode == 1) { // 32KB bank
                uint32_t bank32 = (bank & ~1) % (prg_size / 0x8000);
                return (bank32 * 0x8000) + (addr - 0x8000);
            } else if (mode == 2) { // Fix first 16KB at $8000, switch 16KB at $C000
                if (addr < 0xC000) {
                    return addr - 0x8000;
                } else {
                    return ((bank % num_16k_banks) * 0x4000) + (addr - 0xC000);
                }
            } else { // Switch 16KB at $8000, fix last 16KB at $C000
                if (addr < 0xC000) {
                    return ((bank % num_16k_banks) * 0x4000) + (addr - 0x8000);
                } else {
                    return ((num_16k_banks - 1) * 0x4000) + (addr - 0xC000);
                }
            }
        }
        case 2: { // UxROM
            uint32_t num_16k_banks = prg_size / 0x4000;
            if (num_16k_banks == 0) num_16k_banks = 1;
            if (addr < 0xC000) {
                return ((nes->uxrom_bank % num_16k_banks) * 0x4000) + (addr - 0x8000);
            } else {
                return ((num_16k_banks - 1) * 0x4000) + (addr - 0xC000);
            }
        }
        case 3: { // CNROM
            return (addr - 0x8000) % prg_size;
        }
        case 4: { // MMC3
            uint32_t num_8k_banks = prg_size / 0x2000;
            if (num_8k_banks == 0) num_8k_banks = 1;
            bool prg_mode = (nes->mmc3_bank_select & 0x40) != 0;
            uint32_t b8000, bA000, bC000, bE000;

            if (!prg_mode) {
                b8000 = nes->mmc3_regs[6] % num_8k_banks;
                bA000 = nes->mmc3_regs[7] % num_8k_banks;
                bC000 = (num_8k_banks >= 2) ? (num_8k_banks - 2) : 0;
                bE000 = num_8k_banks - 1;
            } else {
                b8000 = (num_8k_banks >= 2) ? (num_8k_banks - 2) : 0;
                bA000 = nes->mmc3_regs[7] % num_8k_banks;
                bC000 = nes->mmc3_regs[6] % num_8k_banks;
                bE000 = num_8k_banks - 1;
            }

            if (addr < 0xA000) return (b8000 * 0x2000) + (addr - 0x8000);
            if (addr < 0xC000) return (bA000 * 0x2000) + (addr - 0xA000);
            if (addr < 0xE000) return (bC000 * 0x2000) + (addr - 0xC000);
            return (bE000 * 0x2000) + (addr - 0xE000);
        }
        case 7: { // AxROM
            uint32_t bank = nes->axrom_bank & 0x07;
            uint32_t num_32k = prg_size / 0x8000;
            if (num_32k == 0) num_32k = 1;
            return ((bank % num_32k) * 0x8000) + (addr - 0x8000);
        }
        default:
            return (addr - 0x8000) % prg_size;
    }
}

// CHR ROM/RAM mapping
static uint32_t map_chr_addr(NesEmulator* nes, uint16_t addr) {
    uint32_t chr_size = (uint32_t)nes->chr_rom.size();
    if (chr_size == 0) return 0;

    switch (nes->mapper_num) {
        case 0:
        case 2:
        case 7:
            return addr % chr_size;
        case 1: { // MMC1
            bool mode4k = (nes->mmc1_ctrl & 0x10) != 0;
            uint32_t num_4k_banks = chr_size / 0x1000;
            if (num_4k_banks == 0) num_4k_banks = 1;

            if (!mode4k) { // 8KB mode
                uint32_t bank8 = (nes->mmc1_chr0 & ~1) % (chr_size / 0x2000);
                return (bank8 * 0x2000) + addr;
            } else { // 4KB mode
                if (addr < 0x1000) {
                    return ((nes->mmc1_chr0 % num_4k_banks) * 0x1000) + addr;
                } else {
                    return ((nes->mmc1_chr1 % num_4k_banks) * 0x1000) + (addr - 0x1000);
                }
            }
        }
        case 3: { // CNROM
            uint32_t num_8k_banks = chr_size / 0x2000;
            if (num_8k_banks == 0) num_8k_banks = 1;
            return ((nes->cnrom_bank % num_8k_banks) * 0x2000) + addr;
        }
        case 4: { // MMC3
            uint32_t num_1k_banks = chr_size / 0x0400;
            if (num_1k_banks == 0) num_1k_banks = 1;
            bool chr_inversion = (nes->mmc3_bank_select & 0x80) != 0;

            uint32_t bank_1k[8];
            if (!chr_inversion) {
                bank_1k[0] = (nes->mmc3_regs[0] & ~1) % num_1k_banks;
                bank_1k[1] = ((nes->mmc3_regs[0] & ~1) + 1) % num_1k_banks;
                bank_1k[2] = (nes->mmc3_regs[1] & ~1) % num_1k_banks;
                bank_1k[3] = ((nes->mmc3_regs[1] & ~1) + 1) % num_1k_banks;
                bank_1k[4] = nes->mmc3_regs[2] % num_1k_banks;
                bank_1k[5] = nes->mmc3_regs[3] % num_1k_banks;
                bank_1k[6] = nes->mmc3_regs[4] % num_1k_banks;
                bank_1k[7] = nes->mmc3_regs[5] % num_1k_banks;
            } else {
                bank_1k[0] = nes->mmc3_regs[2] % num_1k_banks;
                bank_1k[1] = nes->mmc3_regs[3] % num_1k_banks;
                bank_1k[2] = nes->mmc3_regs[4] % num_1k_banks;
                bank_1k[3] = nes->mmc3_regs[5] % num_1k_banks;
                bank_1k[4] = (nes->mmc3_regs[0] & ~1) % num_1k_banks;
                bank_1k[5] = ((nes->mmc3_regs[0] & ~1) + 1) % num_1k_banks;
                bank_1k[6] = (nes->mmc3_regs[1] & ~1) % num_1k_banks;
                bank_1k[7] = ((nes->mmc3_regs[1] & ~1) + 1) % num_1k_banks;
            }

            int slot = addr / 0x0400;
            return (bank_1k[slot] * 0x0400) + (addr % 0x0400);
        }
        default:
            return addr % chr_size;
    }
}

// CPU Memory Access
uint8_t NesEmulator::cpu_read(uint16_t addr) {
    if (addr < 0x2000) {
        return ram[addr & 0x07FF];
    } else if (addr < 0x4000) { // PPU registers
        uint16_t reg = addr & 0x2007;
        switch (reg) {
            case 0x2002: { // PPUSTATUS
                uint8_t res = (ppustatus & 0xE0) | (ppu_read_buf & 0x1F);
                ppustatus &= ~0x80; // Clear VBlank flag
                w = false;          // Reset Loopy write toggle
                return res;
            }
            case 0x2004: // OAMDATA
                return oam[oam_addr];
            case 0x2007: { // PPUDATA
                uint8_t res = ppu_read_buf;
                ppu_read_buf = ppu_read(v & 0x3FFF);
                if ((v & 0x3FFF) >= 0x3F00) {
                    res = ppu_read_buf; // Palette data returns immediately
                }
                v = (v + ((ppuctrl & 0x04) ? 32 : 1)) & 0x7FFF;
                return res;
            }
            default:
                return 0;
        }
    } else if (addr == 0x4016) { // Controller 1
        uint8_t bit = 0;
        if (controller_shift < 8) {
            bit = (controller_state >> controller_shift) & 1;
            if (!controller_strobe) {
                controller_shift++;
            }
        } else {
            bit = 1;
        }
        return 0x40 | bit;
    } else if (addr == 0x4017) { // Controller 2 (empty)
        return 0x40;
    } else if (addr >= 0x6000 && addr < 0x8000) {
        return prg_ram[addr - 0x6000];
    } else if (addr >= 0x8000) {
        uint32_t prg_idx = map_prg_addr(this, addr);
        if (prg_idx < prg_rom.size()) {
            return prg_rom[prg_idx];
        }
    }
    return 0;
}

void NesEmulator::cpu_write(uint16_t addr, uint8_t val) {
    if (addr < 0x2000) {
        ram[addr & 0x07FF] = val;
    } else if (addr < 0x4000) {
        uint16_t reg = addr & 0x2007;
        switch (reg) {
            case 0x2000: { // PPUCTRL
                bool old_nmi = (ppuctrl & 0x80) != 0;
                ppuctrl = val;
                t = (t & 0xF3FF) | (((uint16_t)(val & 0x03)) << 10);
                if (!old_nmi && (val & 0x80) && (ppustatus & 0x80)) {
                    nmi_pending = true;
                }
                break;
            }
            case 0x2001: // PPUMASK
                ppumask = val;
                break;
            case 0x2003: // OAMADDR
                oam_addr = val;
                break;
            case 0x2004: // OAMDATA
                oam[oam_addr++] = val;
                break;
            case 0x2005: // PPUSCROLL
                if (!w) {
                    t = (t & 0x7FE0) | ((val >> 3) & 0x1F);
                    x_scroll = val & 0x07;
                    w = true;
                } else {
                    t = (t & 0x0C1F) | (((uint16_t)(val & 0x07)) << 12) | (((uint16_t)(val >> 3)) << 5);
                    w = false;
                }
                break;
            case 0x2006: // PPUADDR
                if (!w) {
                    t = (t & 0x00FF) | (((uint16_t)(val & 0x3F)) << 8);
                    w = true;
                } else {
                    t = (t & 0xFF00) | val;
                    v = t;
                    w = false;
                }
                break;
            case 0x2007: // PPUDATA
                ppu_write(v & 0x3FFF, val);
                v = (v + ((ppuctrl & 0x04) ? 32 : 1)) & 0x7FFF;
                break;
        }
    } else if (addr == 0x4014) { // OAMDMA
        uint16_t dma_src = ((uint16_t)val) << 8;
        for (int i = 0; i < 256; i++) {
            oam[(oam_addr + i) & 0xFF] = cpu_read(dma_src + i);
        }
        cpu_cycles += 513;
    } else if (addr >= 0x4000 && addr <= 0x4013) {
        // APU registers
        switch (addr) {
            case 0x4000:
                pulse1_duty = (val >> 6) & 0x03;
                pulse1_length_halt = (val & 0x20) != 0;
                pulse1_constant_vol = (val & 0x10) != 0;
                pulse1_volume = val & 0x0F;
                break;
            case 0x4001:
                pulse1_sweep_enabled = (val & 0x80) != 0;
                pulse1_sweep_period = ((val >> 4) & 0x07) + 1;
                pulse1_sweep_negate = (val & 0x08) != 0;
                pulse1_sweep_shift = val & 0x07;
                pulse1_sweep_reload = true;
                break;
            case 0x4002:
                pulse1_timer_period = (pulse1_timer_period & 0x0700) | val;
                break;
            case 0x4003:
                pulse1_timer_period = (pulse1_timer_period & 0x00FF) | (((uint16_t)(val & 0x07)) << 8);
                if (pulse1_enabled) pulse1_length = LENGTH_TABLE[(val >> 3) & 0x1F];
                pulse1_duty_pos = 0;
                pulse1_env_reload = true;
                break;
            case 0x4004:
                pulse2_duty = (val >> 6) & 0x03;
                pulse2_length_halt = (val & 0x20) != 0;
                pulse2_constant_vol = (val & 0x10) != 0;
                pulse2_volume = val & 0x0F;
                break;
            case 0x4005:
                pulse2_sweep_enabled = (val & 0x80) != 0;
                pulse2_sweep_period = ((val >> 4) & 0x07) + 1;
                pulse2_sweep_negate = (val & 0x08) != 0;
                pulse2_sweep_shift = val & 0x07;
                pulse2_sweep_reload = true;
                break;
            case 0x4006:
                pulse2_timer_period = (pulse2_timer_period & 0x0700) | val;
                break;
            case 0x4007:
                pulse2_timer_period = (pulse2_timer_period & 0x00FF) | (((uint16_t)(val & 0x07)) << 8);
                if (pulse2_enabled) pulse2_length = LENGTH_TABLE[(val >> 3) & 0x1F];
                pulse2_duty_pos = 0;
                pulse2_env_reload = true;
                break;
            case 0x4008:
                triangle_length_halt = (val & 0x80) != 0;
                triangle_linear_reload_val = val & 0x7F;
                break;
            case 0x400A:
                triangle_timer_period = (triangle_timer_period & 0x0700) | val;
                break;
            case 0x400B:
                triangle_timer_period = (triangle_timer_period & 0x00FF) | (((uint16_t)(val & 0x07)) << 8);
                if (triangle_enabled) triangle_length = LENGTH_TABLE[(val >> 3) & 0x1F];
                triangle_linear_reload_flag = true;
                break;
            case 0x400C:
                noise_length_halt = (val & 0x20) != 0;
                noise_constant_vol = (val & 0x10) != 0;
                noise_volume = val & 0x0F;
                break;
            case 0x400E:
                noise_mode = (val & 0x80) != 0;
                noise_timer_period = NOISE_PERIOD[val & 0x0F];
                break;
            case 0x400F:
                if (noise_enabled) noise_length = LENGTH_TABLE[(val >> 3) & 0x1F];
                noise_env_reload = true;
                break;
        }
    } else if (addr == 0x4015) { // APU Status
        pulse1_enabled = (val & 0x01) != 0;
        if (!pulse1_enabled) pulse1_length = 0;

        pulse2_enabled = (val & 0x02) != 0;
        if (!pulse2_enabled) pulse2_length = 0;

        triangle_enabled = (val & 0x04) != 0;
        if (!triangle_enabled) triangle_length = 0;

        noise_enabled = (val & 0x08) != 0;
        if (!noise_enabled) noise_length = 0;
    } else if (addr == 0x4016) { // Controller strobe
        if ((val & 1) == 0 && controller_strobe) {
            controller_shift = 0;
        }
        controller_strobe = (val & 1) != 0;
    } else if (addr == 0x4017) { // APU Frame counter
        frame_counter_mode = (val >> 7) & 1;
        frame_counter_irq_disable = (val & 0x40) != 0;
        frame_counter_cycle = 0;
        if (frame_counter_mode == 1) {
            clock_apu_quarter_frame();
            clock_apu_half_frame();
        }
    } else if (addr >= 0x6000 && addr < 0x8000) {
        prg_ram[addr - 0x6000] = val;
    } else if (addr >= 0x8000) {
        // Mapper writes
        switch (mapper_num) {
            case 1: { // MMC1
                if (val & 0x80) {
                    mmc1_shift_reg = 0x10;
                    mmc1_ctrl |= 0x0C;
                } else {
                    bool complete = (mmc1_shift_reg & 1) != 0;
                    mmc1_shift_reg = (mmc1_shift_reg >> 1) | ((val & 1) << 4);
                    if (complete) {
                        uint8_t data = mmc1_shift_reg;
                        mmc1_shift_reg = 0x10;
                        if (addr < 0xA000) {
                            mmc1_ctrl = data;
                            int m = data & 0x03;
                            if (m == 0) mirror_mode = MIRROR_SINGLE_LOW;
                            else if (m == 1) mirror_mode = MIRROR_SINGLE_HIGH;
                            else if (m == 2) mirror_mode = MIRROR_VERTICAL;
                            else mirror_mode = MIRROR_HORIZONTAL;
                        } else if (addr < 0xC000) {
                            mmc1_chr0 = data;
                        } else if (addr < 0xE000) {
                            mmc1_chr1 = data;
                        } else {
                            mmc1_prg = data;
                        }
                    }
                }
                break;
            }
            case 2: // UxROM
                uxrom_bank = val & 0x0F;
                break;
            case 3: // CNROM
                cnrom_bank = val & 0x03;
                break;
            case 4: { // MMC3
                if (addr < 0xA000) {
                    if ((addr & 1) == 0) {
                        mmc3_bank_select = val;
                    } else {
                        mmc3_regs[mmc3_bank_select & 0x07] = val;
                    }
                } else if (addr < 0xC000) {
                    if ((addr & 1) == 0) {
                        mirror_mode = (val & 1) ? MIRROR_HORIZONTAL : MIRROR_VERTICAL;
                    }
                } else if (addr < 0xE000) {
                    if ((addr & 1) == 0) {
                        mmc3_irq_latch = val;
                    } else {
                        mmc3_irq_counter = 0;
                        mmc3_irq_reload = true;
                    }
                } else {
                    if ((addr & 1) == 0) {
                        mmc3_irq_enabled = false;
                        irq_pending = false;
                    } else {
                        mmc3_irq_enabled = true;
                    }
                }
                break;
            }
            case 7: { // AxROM
                axrom_bank = val & 0x07;
                mirror_mode = (val & 0x10) ? MIRROR_SINGLE_HIGH : MIRROR_SINGLE_LOW;
                break;
            }
        }
    }
}

// PPU Memory Access
uint8_t NesEmulator::ppu_read(uint16_t addr) {
    addr &= 0x3FFF;
    if (addr < 0x2000) {
        uint32_t chr_idx = map_chr_addr(this, addr);
        if (chr_idx < chr_rom.size()) {
            return chr_rom[chr_idx];
        }
        return 0;
    } else if (addr < 0x3F00) {
        uint16_t vram_addr = mirror_nametable_addr(addr, mirror_mode);
        return vram[vram_addr % 2048];
    } else {
        uint16_t pal_addr = addr & 0x1F;
        if ((pal_addr & 0x13) == 0x10) pal_addr &= ~0x10;
        return palette[pal_addr];
    }
}

void NesEmulator::ppu_write(uint16_t addr, uint8_t val) {
    addr &= 0x3FFF;
    if (addr < 0x2000) {
        if (is_chr_ram) {
            uint32_t chr_idx = map_chr_addr(this, addr);
            if (chr_idx < chr_rom.size()) {
                chr_rom[chr_idx] = val;
            }
        }
    } else if (addr < 0x3F00) {
        uint16_t vram_addr = mirror_nametable_addr(addr, mirror_mode);
        vram[vram_addr % 2048] = val;
    } else {
        uint16_t pal_addr = addr & 0x1F;
        if ((pal_addr & 0x13) == 0x10) pal_addr &= ~0x10;
        palette[pal_addr] = val & 0x3F;
    }
}

void NesEmulator::mmc3_clock_scanline() {
    if (mapper_num != 4) return;
    if (mmc3_irq_counter == 0 || mmc3_irq_reload) {
        mmc3_irq_counter = mmc3_irq_latch;
        mmc3_irq_reload = false;
    } else {
        mmc3_irq_counter--;
    }
    if (mmc3_irq_counter == 0 && mmc3_irq_enabled) {
        irq_pending = true;
    }
}

// Render 1 NES pixel at (dot - 1, scanline)
void NesEmulator::render_pixel() {
    int px = dot - 1;
    int py = scanline;
    if (px < 0 || px >= NES_SCREEN_WIDTH || py < 0 || py >= NES_SCREEN_HEIGHT) return;

    bool show_bg = (ppumask & 0x08) != 0;
    bool show_sprites = (ppumask & 0x10) != 0;
    bool clip_bg = (ppumask & 0x02) == 0;
    bool clip_sprites = (ppumask & 0x04) == 0;

    uint8_t bg_pixel = 0;
    uint8_t bg_palette = 0;

    if (show_bg && (!clip_bg || px >= 8)) {
        uint16_t fine_x = (x_scroll + (px & 7)) & 7;
        uint16_t fine_y = (v >> 12) & 7;
        uint16_t coarse_x = v & 0x1F;
        uint16_t coarse_y = (v >> 5) & 0x1F;
        uint16_t nt = (v >> 10) & 3;

        // Effective nametable address
        uint16_t tile_addr = 0x2000 | (nt << 10) | (coarse_y << 5) | coarse_x;
        uint8_t tile_idx = ppu_read(tile_addr);

        uint16_t bg_pattern_base = (ppuctrl & 0x10) ? 0x1000 : 0x0000;
        uint16_t pt_addr = bg_pattern_base | (((uint16_t)tile_idx) << 4) | fine_y;

        uint8_t low_byte = ppu_read(pt_addr);
        uint8_t high_byte = ppu_read(pt_addr + 8);

        uint8_t bit = 7 - fine_x;
        uint8_t p0 = (low_byte >> bit) & 1;
        uint8_t p1 = (high_byte >> bit) & 1;
        bg_pixel = (p1 << 1) | p0;

        if (bg_pixel != 0) {
            uint16_t attr_addr = 0x23C0 | (nt << 10) | ((coarse_y >> 2) << 3) | (coarse_x >> 2);
            uint8_t attr_byte = ppu_read(attr_addr);
            int shift = ((coarse_y & 2) ? 4 : 0) + ((coarse_x & 2) ? 2 : 0);
            bg_palette = (attr_byte >> shift) & 3;
        }
    }

    uint8_t sprite_pixel = 0;
    uint8_t sprite_palette = 0;
    bool sprite_priority = false;
    bool is_sprite0 = false;

    if (show_sprites && (!clip_sprites || px >= 8)) {
        bool sprite_16 = (ppuctrl & 0x20) != 0;
        int sprite_height = sprite_16 ? 16 : 8;

        for (int i = 0; i < 64; i++) {
            int s_y = (int)oam[i * 4] + 1;
            int s_tile = oam[i * 4 + 1];
            uint8_t s_attr = oam[i * 4 + 2];
            int s_x = (int)oam[i * 4 + 3];

            if (py < s_y || py >= s_y + sprite_height) continue;
            if (px < s_x || px >= s_x + 8) continue;

            int row = py - s_y;
            if (s_attr & 0x80) row = sprite_height - 1 - row; // V flip

            int col = px - s_x;
            if (s_attr & 0x40) col = 7 - col; // H flip

            uint16_t pt_addr;
            if (!sprite_16) {
                uint16_t base = (ppuctrl & 0x08) ? 0x1000 : 0x0000;
                pt_addr = base | (((uint16_t)s_tile) << 4) | (row & 7);
            } else {
                uint16_t base = (s_tile & 1) ? 0x1000 : 0x0000;
                uint8_t t_num = s_tile & ~1;
                if (row >= 8) {
                    t_num++;
                    row -= 8;
                }
                pt_addr = base | (((uint16_t)t_num) << 4) | (row & 7);
            }

            uint8_t low = ppu_read(pt_addr);
            uint8_t high = ppu_read(pt_addr + 8);
            uint8_t bit = 7 - col;
            uint8_t p0 = (low >> bit) & 1;
            uint8_t p1 = (high >> bit) & 1;
            uint8_t sp_pix = (p1 << 1) | p0;

            if (sp_pix != 0) {
                sprite_pixel = sp_pix;
                sprite_palette = (s_attr & 3) + 4;
                sprite_priority = (s_attr & 0x20) != 0;
                if (i == 0) is_sprite0 = true;
                break;
            }
        }
    }

    // Sprite 0 Hit detection
    if (is_sprite0 && bg_pixel != 0 && px < 255 && !sprite0_hit) {
        sprite0_hit = true;
        ppustatus |= 0x40;
    }

    // Determine final color
    uint8_t final_color_idx = 0;
    if (bg_pixel == 0 && sprite_pixel == 0) {
        final_color_idx = palette[0];
    } else if (bg_pixel != 0 && sprite_pixel == 0) {
        final_color_idx = palette[bg_palette * 4 + bg_pixel];
    } else if (bg_pixel == 0 && sprite_pixel != 0) {
        final_color_idx = palette[sprite_palette * 4 + sprite_pixel];
    } else { // Both non-transparent
        if (sprite_priority) { // Behind background
            final_color_idx = palette[bg_palette * 4 + bg_pixel];
        } else { // In front of background
            final_color_idx = palette[sprite_palette * 4 + sprite_pixel];
        }
    }

    frame_buffer[py * NES_SCREEN_WIDTH + px] = NES_PALETTE_RGB565[final_color_idx & 0x3F];
}

void NesEmulator::step_ppu() {
    bool rendering_enabled = (ppumask & 0x18) != 0;

    if (scanline >= 0 && scanline < 240) { // Visible scanlines
        if (dot >= 1 && dot <= 256) {
            render_pixel();

            // Horizontal coarse X increment every 8 dots
            if (rendering_enabled && (dot & 7) == 0) {
                if ((v & 0x001F) == 31) {
                    v = (v & ~0x001F) ^ 0x0400; // Wrap X and toggle nametable bit 0
                } else {
                    v++;
                }
            }
            if (rendering_enabled && dot == 256) {
                // Vertical fine Y increment
                if ((v & 0x7000) != 0x7000) {
                    v += 0x1000;
                } else {
                    v &= ~0x7000;
                    int y = (v & 0x03E0) >> 5;
                    if (y == 29) {
                        y = 0;
                        v ^= 0x0800; // Toggle nametable bit 1
                    } else if (y == 31) {
                        y = 0;
                    } else {
                        y++;
                    }
                    v = (v & ~0x03E0) | (y << 5);
                }
            }
        } else if (dot == 257) {
            if (rendering_enabled) {
                // Copy horizontal scroll bits from t to v
                v = (v & ~0x041F) | (t & 0x041F);
            }
        } else if (dot == 260) {
            if (rendering_enabled) {
                mmc3_clock_scanline();
            }
        }
    } else if (scanline == 241 && dot == 1) { // VBlank
        ppustatus |= 0x80;
        if (ppuctrl & 0x80) {
            nmi_pending = true;
        }
    } else if (scanline == 261) { // Pre-render scanline
        if (dot == 1) {
            ppustatus &= ~(0x80 | 0x40 | 0x20); // Clear VBlank, Sprite 0, Overflow
            sprite0_hit = false;
        } else if (dot >= 280 && dot <= 304) {
            if (rendering_enabled) {
                // Copy vertical scroll bits from t to v
                v = (v & ~0x7BE0) | (t & 0x7BE0);
            }
        }
    }

    dot++;
    if (dot > 340) {
        dot = 0;
        scanline++;
        if (scanline > 261) {
            scanline = 0;
            frame_odd = !frame_odd;
        }
    }
}

void NesEmulator::clock_apu_quarter_frame() {
    // Envelope generators
    // Pulse 1
    if (pulse1_env_reload) {
        pulse1_env_decay = 15;
        pulse1_env_counter = pulse1_volume;
        pulse1_env_reload = false;
    } else {
        if (pulse1_env_counter > 0) {
            pulse1_env_counter--;
        } else {
            pulse1_env_counter = pulse1_volume;
            if (pulse1_env_decay > 0) {
                pulse1_env_decay--;
            } else if (pulse1_length_halt) {
                pulse1_env_decay = 15;
            }
        }
    }

    // Pulse 2
    if (pulse2_env_reload) {
        pulse2_env_decay = 15;
        pulse2_env_counter = pulse2_volume;
        pulse2_env_reload = false;
    } else {
        if (pulse2_env_counter > 0) {
            pulse2_env_counter--;
        } else {
            pulse2_env_counter = pulse2_volume;
            if (pulse2_env_decay > 0) {
                pulse2_env_decay--;
            } else if (pulse2_length_halt) {
                pulse2_env_decay = 15;
            }
        }
    }

    // Noise
    if (noise_env_reload) {
        noise_env_decay = 15;
        noise_env_counter = noise_volume;
        noise_env_reload = false;
    } else {
        if (noise_env_counter > 0) {
            noise_env_counter--;
        } else {
            noise_env_counter = noise_volume;
            if (noise_env_decay > 0) {
                noise_env_decay--;
            } else if (noise_length_halt) {
                noise_env_decay = 15;
            }
        }
    }

    // Triangle linear counter
    if (triangle_linear_reload_flag) {
        triangle_linear_counter = triangle_linear_reload_val;
    } else if (triangle_linear_counter > 0) {
        triangle_linear_counter--;
    }
    if (!triangle_length_halt) {
        triangle_linear_reload_flag = false;
    }
}

void NesEmulator::clock_apu_half_frame() {
    clock_apu_quarter_frame();

    // Length counters & sweeps
    if (!pulse1_length_halt && pulse1_length > 0) pulse1_length--;
    if (!pulse2_length_halt && pulse2_length > 0) pulse2_length--;
    if (!triangle_length_halt && triangle_length > 0) triangle_length--;
    if (!noise_length_halt && noise_length > 0) noise_length--;

    // Pulse 1 sweep
    if (pulse1_sweep_enabled && pulse1_sweep_shift > 0) {
        uint16_t delta = pulse1_timer_period >> pulse1_sweep_shift;
        if (pulse1_sweep_negate) {
            if (pulse1_timer_period >= delta) pulse1_timer_period -= delta;
            else pulse1_timer_period = 0;
        } else {
            pulse1_timer_period += delta;
        }
    }

    // Pulse 2 sweep
    if (pulse2_sweep_enabled && pulse2_sweep_shift > 0) {
        uint16_t delta = pulse2_timer_period >> pulse2_sweep_shift;
        if (pulse2_sweep_negate) {
            if (pulse2_timer_period >= delta) pulse2_timer_period -= delta;
            else pulse2_timer_period = 0;
        } else {
            pulse2_timer_period += delta;
        }
    }
}

void NesEmulator::generate_audio_sample() {
    // Pulse 1 output
    int p1 = 0;
    if (pulse1_enabled && pulse1_length > 0 && pulse1_timer_period >= 8) {
        if (DUTY_CYCLES[pulse1_duty][pulse1_duty_pos]) {
            p1 = pulse1_constant_vol ? pulse1_volume : pulse1_env_decay;
        }
    }

    // Pulse 2 output
    int p2 = 0;
    if (pulse2_enabled && pulse2_length > 0 && pulse2_timer_period >= 8) {
        if (DUTY_CYCLES[pulse2_duty][pulse2_duty_pos]) {
            p2 = pulse2_constant_vol ? pulse2_volume : pulse2_env_decay;
        }
    }

    // Triangle output
    int tri = 0;
    if (triangle_enabled && triangle_length > 0 && triangle_linear_counter > 0) {
        tri = TRIANGLE_SEQUENCE[triangle_step];
    }

    // Noise output
    int n = 0;
    if (noise_enabled && noise_length > 0 && !(noise_shift_reg & 1)) {
        n = noise_constant_vol ? noise_volume : noise_env_decay;
    }

    // Linear approximation of NES DAC mixer
    float pulse_out = (p1 + p2 > 0) ? (95.88f / ((8128.0f / (float)(p1 + p2)) + 100.0f)) : 0.0f;
    float tnd_denom = (tri / 8227.0f) + (n / 12241.0f);
    float tnd_out = (tnd_denom > 0.0f) ? (159.79f / ((1.0f / tnd_denom) + 100.0f)) : 0.0f;

    float mixed = (pulse_out + tnd_out) * 32767.0f * 0.9f;
    int16_t sample = (int16_t)std::clamp(mixed, -32768.0f, 32767.0f);

    audio_samples.push_back(sample); // Left
    audio_samples.push_back(sample); // Right
}

void NesEmulator::step_apu() {
    // Pulse 1 timer
    if (pulse1_timer > 0) {
        pulse1_timer--;
    } else {
        pulse1_timer = (pulse1_timer_period + 1) * 2;
        pulse1_duty_pos = (pulse1_duty_pos + 1) & 7;
    }

    // Pulse 2 timer
    if (pulse2_timer > 0) {
        pulse2_timer--;
    } else {
        pulse2_timer = (pulse2_timer_period + 1) * 2;
        pulse2_duty_pos = (pulse2_duty_pos + 1) & 7;
    }

    // Triangle timer
    if (triangle_timer > 0) {
        triangle_timer--;
    } else {
        triangle_timer = triangle_timer_period + 1;
        if (triangle_length > 0 && triangle_linear_counter > 0) {
            triangle_step = (triangle_step + 1) & 31;
        }
    }

    // Noise timer
    if (noise_timer > 0) {
        noise_timer--;
    } else {
        noise_timer = noise_timer_period;
        uint16_t bit = (noise_shift_reg & 1) ^ ((noise_shift_reg >> (noise_mode ? 6 : 1)) & 1);
        noise_shift_reg = (noise_shift_reg >> 1) | (bit << 14);
    }

    // Frame counter
    frame_counter_cycle++;
    if (frame_counter_mode == 0) { // 4-step sequence
        if (frame_counter_cycle == 3728) clock_apu_quarter_frame();
        else if (frame_counter_cycle == 7456) clock_apu_half_frame();
        else if (frame_counter_cycle == 11185) clock_apu_quarter_frame();
        else if (frame_counter_cycle == 14914) {
            clock_apu_half_frame();
            if (!frame_counter_irq_disable) irq_pending = true;
            frame_counter_cycle = 0;
        }
    } else { // 5-step sequence
        if (frame_counter_cycle == 3728) clock_apu_quarter_frame();
        else if (frame_counter_cycle == 7456) clock_apu_half_frame();
        else if (frame_counter_cycle == 11185) clock_apu_quarter_frame();
        else if (frame_counter_cycle == 18640) {
            clock_apu_half_frame();
            frame_counter_cycle = 0;
        }
    }

    // Fractional accumulator downsampling: 1,789,773 CPU Hz -> 44,100 Audio Hz
    sample_accumulator += NES_AUDIO_SAMPLE_RATE;
    if (sample_accumulator >= 1789773) {
        sample_accumulator -= 1789773;
        generate_audio_sample();
    }
}

// 6502 CPU Instruction Emulation
static inline void set_flag(NesEmulator* nes, uint8_t flag, bool cond) {
    if (cond) nes->p |= flag;
    else nes->p &= ~flag;
}

static inline void set_zn(NesEmulator* nes, uint8_t val) {
    set_flag(nes, 0x02, val == 0);
    set_flag(nes, 0x80, (val & 0x80) != 0);
}

static inline void push(NesEmulator* nes, uint8_t val) {
    nes->ram[0x0100 | nes->sp] = val;
    nes->sp--;
}

static inline uint8_t pop(NesEmulator* nes) {
    nes->sp++;
    return nes->ram[0x0100 | nes->sp];
}

static int execute_cpu_instruction(NesEmulator* nes) {
    if (nes->nmi_pending) {
        nes->nmi_pending = false;
        push(nes, (nes->pc >> 8) & 0xFF);
        push(nes, nes->pc & 0xFF);
        push(nes, (nes->p | 0x20) & ~0x10);
        nes->p |= 0x04; // Set interrupt disable
        uint16_t lo = nes->cpu_read(0xFFFA);
        uint16_t hi = nes->cpu_read(0xFFFB);
        nes->pc = (hi << 8) | lo;
        return 7;
    }

    if (nes->irq_pending && !(nes->p & 0x04)) {
        push(nes, (nes->pc >> 8) & 0xFF);
        push(nes, nes->pc & 0xFF);
        push(nes, (nes->p | 0x20) & ~0x10);
        nes->p |= 0x04;
        uint16_t lo = nes->cpu_read(0xFFFE);
        uint16_t hi = nes->cpu_read(0xFFFF);
        nes->pc = (hi << 8) | lo;
        return 7;
    }

    uint8_t opcode = nes->cpu_read(nes->pc++);
    int cycles = 2;

    auto fetch8 = [&]() -> uint8_t { return nes->cpu_read(nes->pc++); };
    auto fetch16 = [&]() -> uint16_t {
        uint8_t lo = fetch8();
        uint8_t hi = fetch8();
        return (hi << 8) | lo;
    };

    switch (opcode) {
        // ADC
        case 0x69: case 0x65: case 0x75: case 0x6D: case 0x7D: case 0x79: case 0x61: case 0x71: {
            uint16_t addr = 0;
            if (opcode == 0x69) addr = nes->pc++;
            else if (opcode == 0x65) addr = fetch8();
            else if (opcode == 0x75) addr = (fetch8() + nes->x) & 0xFF;
            else if (opcode == 0x6D) addr = fetch16();
            else if (opcode == 0x7D) addr = fetch16() + nes->x;
            else if (opcode == 0x79) addr = fetch16() + nes->y;
            else if (opcode == 0x61) { uint8_t ptr = (fetch8() + nes->x) & 0xFF; addr = nes->cpu_read(ptr) | (nes->cpu_read((ptr + 1) & 0xFF) << 8); }
            else if (opcode == 0x71) { uint8_t ptr = fetch8(); addr = (nes->cpu_read(ptr) | (nes->cpu_read((ptr + 1) & 0xFF) << 8)) + nes->y; }
            uint8_t val = nes->cpu_read(addr);
            uint16_t sum = nes->a + val + (nes->p & 1);
            set_flag(nes, 0x01, sum > 0xFF);
            set_flag(nes, 0x40, (~(nes->a ^ val) & (nes->a ^ sum) & 0x80) != 0);
            nes->a = sum & 0xFF;
            set_zn(nes, nes->a);
            break;
        }
        // SBC
        case 0xE9: case 0xEB: case 0xE5: case 0xF5: case 0xED: case 0xFD: case 0xF9: case 0xE1: case 0xF1: {
            uint16_t addr = 0;
            if (opcode == 0xE9 || opcode == 0xEB) addr = nes->pc++;
            else if (opcode == 0xE5) addr = fetch8();
            else if (opcode == 0xF5) addr = (fetch8() + nes->x) & 0xFF;
            else if (opcode == 0xED) addr = fetch16();
            else if (opcode == 0xFD) addr = fetch16() + nes->x;
            else if (opcode == 0xF9) addr = fetch16() + nes->y;
            else if (opcode == 0xE1) { uint8_t ptr = (fetch8() + nes->x) & 0xFF; addr = nes->cpu_read(ptr) | (nes->cpu_read((ptr + 1) & 0xFF) << 8); }
            else if (opcode == 0xF1) { uint8_t ptr = fetch8(); addr = (nes->cpu_read(ptr) | (nes->cpu_read((ptr + 1) & 0xFF) << 8)) + nes->y; }
            uint8_t val = nes->cpu_read(addr) ^ 0xFF;
            uint16_t sum = nes->a + val + (nes->p & 1);
            set_flag(nes, 0x01, sum > 0xFF);
            set_flag(nes, 0x40, (~(nes->a ^ val) & (nes->a ^ sum) & 0x80) != 0);
            nes->a = sum & 0xFF;
            set_zn(nes, nes->a);
            break;
        }
        // AND
        case 0x29: case 0x25: case 0x35: case 0x2D: case 0x3D: case 0x39: case 0x21: case 0x31: {
            uint16_t addr = (opcode == 0x29) ? nes->pc++ : (opcode == 0x25 ? fetch8() : (opcode == 0x35 ? (fetch8() + nes->x) & 0xFF : (opcode == 0x2D ? fetch16() : (opcode == 0x3D ? fetch16() + nes->x : (opcode == 0x39 ? fetch16() + nes->y : (opcode == 0x21 ? (nes->cpu_read((fetch8() + nes->x) & 0xFF) | (nes->cpu_read((fetch8() + nes->x + 1) & 0xFF) << 8)) : (nes->cpu_read(fetch8()) | (nes->cpu_read((fetch8() + 1) & 0xFF) << 8)) + nes->y))))));
            nes->a &= nes->cpu_read(addr);
            set_zn(nes, nes->a);
            break;
        }
        // ORA
        case 0x09: case 0x05: case 0x15: case 0x0D: case 0x1D: case 0x19: case 0x01: case 0x11: {
            uint16_t addr = (opcode == 0x09) ? nes->pc++ : (opcode == 0x05 ? fetch8() : (opcode == 0x15 ? (fetch8() + nes->x) & 0xFF : (opcode == 0x0D ? fetch16() : (opcode == 0x1D ? fetch16() + nes->x : (opcode == 0x19 ? fetch16() + nes->y : (opcode == 0x01 ? (nes->cpu_read((fetch8() + nes->x) & 0xFF) | (nes->cpu_read((fetch8() + nes->x + 1) & 0xFF) << 8)) : (nes->cpu_read(fetch8()) | (nes->cpu_read((fetch8() + 1) & 0xFF) << 8)) + nes->y))))));
            nes->a |= nes->cpu_read(addr);
            set_zn(nes, nes->a);
            break;
        }
        // EOR
        case 0x49: case 0x45: case 0x55: case 0x4D: case 0x5D: case 0x59: case 0x41: case 0x51: {
            uint16_t addr = (opcode == 0x49) ? nes->pc++ : (opcode == 0x45 ? fetch8() : (opcode == 0x55 ? (fetch8() + nes->x) & 0xFF : (opcode == 0x4D ? fetch16() : (opcode == 0x5D ? fetch16() + nes->x : (opcode == 0x59 ? fetch16() + nes->y : (opcode == 0x41 ? (nes->cpu_read((fetch8() + nes->x) & 0xFF) | (nes->cpu_read((fetch8() + nes->x + 1) & 0xFF) << 8)) : (nes->cpu_read(fetch8()) | (nes->cpu_read((fetch8() + 1) & 0xFF) << 8)) + nes->y))))));
            nes->a ^= nes->cpu_read(addr);
            set_zn(nes, nes->a);
            break;
        }
        // LDA
        case 0xA9: nes->a = fetch8(); set_zn(nes, nes->a); break;
        case 0xA5: nes->a = nes->cpu_read(fetch8()); set_zn(nes, nes->a); break;
        case 0xB5: nes->a = nes->cpu_read((fetch8() + nes->x) & 0xFF); set_zn(nes, nes->a); break;
        case 0xAD: nes->a = nes->cpu_read(fetch16()); set_zn(nes, nes->a); break;
        case 0xBD: nes->a = nes->cpu_read(fetch16() + nes->x); set_zn(nes, nes->a); break;
        case 0xB9: nes->a = nes->cpu_read(fetch16() + nes->y); set_zn(nes, nes->a); break;
        case 0xA1: { uint8_t p = (fetch8() + nes->x) & 0xFF; nes->a = nes->cpu_read(nes->cpu_read(p) | (nes->cpu_read((p + 1) & 0xFF) << 8)); set_zn(nes, nes->a); break; }
        case 0xB1: { uint8_t p = fetch8(); nes->a = nes->cpu_read((nes->cpu_read(p) | (nes->cpu_read((p + 1) & 0xFF) << 8)) + nes->y); set_zn(nes, nes->a); break; }

        // LDX
        case 0xA2: nes->x = fetch8(); set_zn(nes, nes->x); break;
        case 0xA6: nes->x = nes->cpu_read(fetch8()); set_zn(nes, nes->x); break;
        case 0xB6: nes->x = nes->cpu_read((fetch8() + nes->y) & 0xFF); set_zn(nes, nes->x); break;
        case 0xAE: nes->x = nes->cpu_read(fetch16()); set_zn(nes, nes->x); break;
        case 0xBE: nes->x = nes->cpu_read(fetch16() + nes->y); set_zn(nes, nes->x); break;

        // LDY
        case 0xA0: nes->y = fetch8(); set_zn(nes, nes->y); break;
        case 0xA4: nes->y = nes->cpu_read(fetch8()); set_zn(nes, nes->y); break;
        case 0xB4: nes->y = nes->cpu_read((fetch8() + nes->x) & 0xFF); set_zn(nes, nes->y); break;
        case 0xAC: nes->y = nes->cpu_read(fetch16()); set_zn(nes, nes->y); break;
        case 0xBC: nes->y = nes->cpu_read(fetch16() + nes->x); set_zn(nes, nes->y); break;

        // STA
        case 0x85: nes->cpu_write(fetch8(), nes->a); break;
        case 0x95: nes->cpu_write((fetch8() + nes->x) & 0xFF, nes->a); break;
        case 0x8D: nes->cpu_write(fetch16(), nes->a); break;
        case 0x9D: nes->cpu_write(fetch16() + nes->x, nes->a); break;
        case 0x99: nes->cpu_write(fetch16() + nes->y, nes->a); break;
        case 0x81: { uint8_t p = (fetch8() + nes->x) & 0xFF; nes->cpu_write(nes->cpu_read(p) | (nes->cpu_read((p + 1) & 0xFF) << 8), nes->a); break; }
        case 0x91: { uint8_t p = fetch8(); nes->cpu_write((nes->cpu_read(p) | (nes->cpu_read((p + 1) & 0xFF) << 8)) + nes->y, nes->a); break; }

        // STX
        case 0x86: nes->cpu_write(fetch8(), nes->x); break;
        case 0x96: nes->cpu_write((fetch8() + nes->y) & 0xFF, nes->x); break;
        case 0x8E: nes->cpu_write(fetch16(), nes->x); break;

        // STY
        case 0x84: nes->cpu_write(fetch8(), nes->y); break;
        case 0x94: nes->cpu_write((fetch8() + nes->x) & 0xFF, nes->y); break;
        case 0x8C: nes->cpu_write(fetch16(), nes->y); break;

        // JMP
        case 0x4C: nes->pc = fetch16(); break;
        case 0x6C: {
            uint16_t ptr = fetch16();
            uint16_t hi_ptr = (ptr & 0xFF00) | ((ptr + 1) & 0x00FF); // 6502 page wrap bug
            nes->pc = nes->cpu_read(ptr) | (nes->cpu_read(hi_ptr) << 8);
            break;
        }

        // JSR & RTS
        case 0x20: {
            uint16_t dest = fetch16();
            uint16_t ret = nes->pc - 1;
            push(nes, (ret >> 8) & 0xFF);
            push(nes, ret & 0xFF);
            nes->pc = dest;
            cycles = 6;
            break;
        }
        case 0x60: {
            uint16_t lo = pop(nes);
            uint16_t hi = pop(nes);
            nes->pc = ((hi << 8) | lo) + 1;
            cycles = 6;
            break;
        }

        // Branching
        case 0x10: { int8_t off = (int8_t)fetch8(); if (!(nes->p & 0x80)) nes->pc += off; break; } // BPL
        case 0x30: { int8_t off = (int8_t)fetch8(); if (nes->p & 0x80) nes->pc += off; break; }    // BMI
        case 0x50: { int8_t off = (int8_t)fetch8(); if (!(nes->p & 0x40)) nes->pc += off; break; } // BVC
        case 0x70: { int8_t off = (int8_t)fetch8(); if (nes->p & 0x40) nes->pc += off; break; }    // BVS
        case 0x90: { int8_t off = (int8_t)fetch8(); if (!(nes->p & 0x01)) nes->pc += off; break; } // BCC
        case 0xB0: { int8_t off = (int8_t)fetch8(); if (nes->p & 0x01) nes->pc += off; break; }    // BCS
        case 0xD0: { int8_t off = (int8_t)fetch8(); if (!(nes->p & 0x02)) nes->pc += off; break; } // BNE
        case 0xF0: { int8_t off = (int8_t)fetch8(); if (nes->p & 0x02) nes->pc += off; break; }    // BEQ

        // CMP, CPX, CPY
        case 0xC9: case 0xC5: case 0xD5: case 0xCD: case 0xDD: case 0xD9: case 0xC1: case 0xD1: {
            uint16_t addr = (opcode == 0xC9) ? nes->pc++ : (opcode == 0xC5 ? fetch8() : (opcode == 0xD5 ? (fetch8() + nes->x) & 0xFF : (opcode == 0xCD ? fetch16() : (opcode == 0xDD ? fetch16() + nes->x : (opcode == 0xD9 ? fetch16() + nes->y : (opcode == 0xC1 ? (nes->cpu_read((fetch8() + nes->x) & 0xFF) | (nes->cpu_read((fetch8() + nes->x + 1) & 0xFF) << 8)) : (nes->cpu_read(fetch8()) | (nes->cpu_read((fetch8() + 1) & 0xFF) << 8)) + nes->y))))));
            uint8_t v = nes->cpu_read(addr);
            set_flag(nes, 0x01, nes->a >= v);
            set_zn(nes, nes->a - v);
            break;
        }
        case 0xE0: case 0xE4: case 0xEC: {
            uint16_t addr = (opcode == 0xE0) ? nes->pc++ : (opcode == 0xE4 ? fetch8() : fetch16());
            uint8_t v = nes->cpu_read(addr);
            set_flag(nes, 0x01, nes->x >= v);
            set_zn(nes, nes->x - v);
            break;
        }
        case 0xC0: case 0xC4: case 0xCC: {
            uint16_t addr = (opcode == 0xC0) ? nes->pc++ : (opcode == 0xC4 ? fetch8() : fetch16());
            uint8_t v = nes->cpu_read(addr);
            set_flag(nes, 0x01, nes->y >= v);
            set_zn(nes, nes->y - v);
            break;
        }

        // INC / DEC
        case 0xE6: case 0xF6: case 0xEE: case 0xFE: {
            uint16_t addr = (opcode == 0xE6 ? fetch8() : (opcode == 0xF6 ? (fetch8() + nes->x) & 0xFF : (opcode == 0xEE ? fetch16() : fetch16() + nes->x)));
            uint8_t v = nes->cpu_read(addr) + 1;
            nes->cpu_write(addr, v);
            set_zn(nes, v);
            break;
        }
        case 0xC6: case 0xD6: case 0xCE: case 0xDE: {
            uint16_t addr = (opcode == 0xC6 ? fetch8() : (opcode == 0xD6 ? (fetch8() + nes->x) & 0xFF : (opcode == 0xCE ? fetch16() : fetch16() + nes->x)));
            uint8_t v = nes->cpu_read(addr) - 1;
            nes->cpu_write(addr, v);
            set_zn(nes, v);
            break;
        }
        case 0xE8: nes->x++; set_zn(nes, nes->x); break; // INX
        case 0xCA: nes->x--; set_zn(nes, nes->x); break; // DEX
        case 0xC8: nes->y++; set_zn(nes, nes->y); break; // INY
        case 0x88: nes->y--; set_zn(nes, nes->y); break; // DEY

        // Register Transfers
        case 0xAA: nes->x = nes->a; set_zn(nes, nes->x); break; // TAX
        case 0x8A: nes->a = nes->x; set_zn(nes, nes->a); break; // TXA
        case 0xA8: nes->y = nes->a; set_zn(nes, nes->y); break; // TAY
        case 0x98: nes->a = nes->y; set_zn(nes, nes->a); break; // TYA
        case 0xBA: nes->x = nes->sp; set_zn(nes, nes->x); break; // TSX
        case 0x9A: nes->sp = nes->x; break;                     // TXS

        // Shifts & Rotates
        case 0x0A: { uint8_t c = (nes->a >> 7) & 1; nes->a <<= 1; set_flag(nes, 0x01, c != 0); set_zn(nes, nes->a); break; } // ASL A
        case 0x06: case 0x16: case 0x0E: case 0x1E: {
            uint16_t addr = (opcode == 0x06 ? fetch8() : (opcode == 0x16 ? (fetch8() + nes->x) & 0xFF : (opcode == 0x0E ? fetch16() : fetch16() + nes->x)));
            uint8_t v = nes->cpu_read(addr);
            set_flag(nes, 0x01, (v & 0x80) != 0);
            v <<= 1;
            nes->cpu_write(addr, v);
            set_zn(nes, v);
            break;
        }
        case 0x4A: { uint8_t c = nes->a & 1; nes->a >>= 1; set_flag(nes, 0x01, c != 0); set_zn(nes, nes->a); break; } // LSR A
        case 0x46: case 0x56: case 0x4E: case 0x5E: {
            uint16_t addr = (opcode == 0x46 ? fetch8() : (opcode == 0x56 ? (fetch8() + nes->x) & 0xFF : (opcode == 0x4E ? fetch16() : fetch16() + nes->x)));
            uint8_t v = nes->cpu_read(addr);
            set_flag(nes, 0x01, (v & 0x01) != 0);
            v >>= 1;
            nes->cpu_write(addr, v);
            set_zn(nes, v);
            break;
        }
        case 0x2A: { uint8_t c = (nes->a >> 7) & 1; nes->a = (nes->a << 1) | (nes->p & 1); set_flag(nes, 0x01, c != 0); set_zn(nes, nes->a); break; } // ROL A
        case 0x26: case 0x36: case 0x2E: case 0x3E: {
            uint16_t addr = (opcode == 0x26 ? fetch8() : (opcode == 0x36 ? (fetch8() + nes->x) & 0xFF : (opcode == 0x2E ? fetch16() : fetch16() + nes->x)));
            uint8_t v = nes->cpu_read(addr);
            uint8_t c = (v >> 7) & 1;
            v = (v << 1) | (nes->p & 1);
            set_flag(nes, 0x01, c != 0);
            nes->cpu_write(addr, v);
            set_zn(nes, v);
            break;
        }
        case 0x6A: { uint8_t c = nes->a & 1; nes->a = (nes->a >> 1) | ((nes->p & 1) << 7); set_flag(nes, 0x01, c != 0); set_zn(nes, nes->a); break; } // ROR A
        case 0x66: case 0x76: case 0x6E: case 0x7E: {
            uint16_t addr = (opcode == 0x66 ? fetch8() : (opcode == 0x76 ? (fetch8() + nes->x) & 0xFF : (opcode == 0x6E ? fetch16() : fetch16() + nes->x)));
            uint8_t v = nes->cpu_read(addr);
            uint8_t c = v & 1;
            v = (v >> 1) | ((nes->p & 1) << 7);
            set_flag(nes, 0x01, c != 0);
            nes->cpu_write(addr, v);
            set_zn(nes, v);
            break;
        }

        // BIT
        case 0x24: case 0x2C: {
            uint16_t addr = (opcode == 0x24) ? fetch8() : fetch16();
            uint8_t v = nes->cpu_read(addr);
            set_flag(nes, 0x02, (nes->a & v) == 0);
            set_flag(nes, 0x40, (v & 0x40) != 0);
            set_flag(nes, 0x80, (v & 0x80) != 0);
            break;
        }

        // Stack instructions
        case 0x48: push(nes, nes->a); break;                          // PHA
        case 0x68: nes->a = pop(nes); set_zn(nes, nes->a); break;    // PLA
        case 0x08: push(nes, nes->p | 0x30); break;                   // PHP
        case 0x28: nes->p = (pop(nes) & ~0x10) | 0x20; break;        // PLP
        case 0x40: { // RTI
            nes->p = (pop(nes) & ~0x10) | 0x20;
            uint16_t lo = pop(nes);
            uint16_t hi = pop(nes);
            nes->pc = (hi << 8) | lo;
            break;
        }

        // Flags
        case 0x18: set_flag(nes, 0x01, false); break; // CLC
        case 0x38: set_flag(nes, 0x01, true); break;  // SEC
        case 0x58: set_flag(nes, 0x04, false); break; // CLI
        case 0x78: set_flag(nes, 0x04, true); break;  // SEI
        case 0xB8: set_flag(nes, 0x40, false); break; // CLV
        case 0xD8: set_flag(nes, 0x08, false); break; // CLD
        case 0xF8: set_flag(nes, 0x08, true); break;  // SED

        // NOP & Unofficial NOPs
        case 0xEA: case 0x1A: case 0x3A: case 0x5A: case 0x7A: case 0xDA: case 0xFA:
            break;
        case 0x04: case 0x44: case 0x64: case 0x80: case 0x82: case 0x89: case 0xC2: case 0xE2:
            nes->pc++; break;
        case 0x0C: case 0x1C: case 0x3C: case 0x5C: case 0x7C: case 0xDC: case 0xFC:
            nes->pc += 2; break;

        default:
            // Unhandled opcode treated as NOP
            break;
    }

    return cycles;
}

// Public API Implementation
NesEmulator* nes_create(void) {
    init_palette_rgb565();
    NesEmulator* nes = new NesEmulator();
    memset(nes->ram, 0, sizeof(nes->ram));
    memset(nes->prg_ram, 0, sizeof(nes->prg_ram));
    memset(nes->vram, 0, sizeof(nes->vram));
    memset(nes->palette, 0, sizeof(nes->palette));
    memset(nes->oam, 0, sizeof(nes->oam));
    memset(nes->frame_buffer, 0, sizeof(nes->frame_buffer));
    nes_reset(nes);
    return nes;
}

void nes_destroy(NesEmulator* nes) {
    if (nes) {
        delete nes;
    }
}

void nes_reset(NesEmulator* nes) {
    if (!nes) return;
    nes->sp = 0xFD;
    nes->p = 0x24; // I and U set
    nes->cpu_cycles = 0;
    nes->nmi_pending = false;
    nes->irq_pending = false;

    nes->ppuctrl = 0;
    nes->ppumask = 0;
    nes->ppustatus = 0;
    nes->oam_addr = 0;
    nes->ppu_read_buf = 0;
    nes->v = 0;
    nes->t = 0;
    nes->x_scroll = 0;
    nes->w = false;
    nes->scanline = 241;
    nes->dot = 0;
    nes->frame_odd = false;
    nes->sprite0_hit = false;

    // Reset APU
    nes->pulse1_enabled = false;
    nes->pulse1_length = 0;
    nes->pulse2_enabled = false;
    nes->pulse2_length = 0;
    nes->triangle_enabled = false;
    nes->triangle_length = 0;
    nes->noise_enabled = false;
    nes->noise_length = 0;
    nes->noise_shift_reg = 1;
    nes->frame_counter_mode = 0;
    nes->frame_counter_cycle = 0;
    nes->sample_accumulator = 0;
    nes->turbo_counter = 0;

    // Reset Mappers
    nes->mmc1_shift_reg = 0x10;
    nes->mmc1_ctrl = 0x0C;
    nes->mmc1_chr0 = 0;
    nes->mmc1_chr1 = 0;
    nes->mmc1_prg = 0;
    nes->uxrom_bank = 0;
    nes->cnrom_bank = 0;
    nes->mmc3_bank_select = 0;
    memset(nes->mmc3_regs, 0, sizeof(nes->mmc3_regs));
    nes->mmc3_irq_counter = 0;
    nes->mmc3_irq_latch = 0;
    nes->mmc3_irq_enabled = false;
    nes->mmc3_irq_reload = false;
    nes->axrom_bank = 0;

    // Read Reset Vector
    uint16_t lo = nes->cpu_read(0xFFFC);
    uint16_t hi = nes->cpu_read(0xFFFD);
    nes->pc = (hi << 8) | lo;
}

bool nes_load_rom(NesEmulator* nes, const uint8_t* data, size_t size) {
    if (!nes || !data || size < 16) return false;

    // Check iNES header "NES\x1A"
    if (data[0] != 'N' || data[1] != 'E' || data[2] != 'S' || data[3] != 0x1A) {
        return false;
    }

    uint8_t prg_16k = data[4];
    uint8_t chr_8k = data[5];
    uint8_t flags6 = data[6];
    uint8_t flags7 = data[7];

    nes->mapper_num = ((flags7 & 0xF0) | (flags6 >> 4));
    nes->mirror_mode = (flags6 & 0x01) ? MIRROR_VERTICAL : MIRROR_HORIZONTAL;
    if (flags6 & 0x08) nes->mirror_mode = MIRROR_FOUR_SCREEN;
    nes->has_prg_ram = (flags6 & 0x02) != 0;

    size_t offset = 16;
    if (flags6 & 0x04) offset += 512; // Skip 512-byte trainer

    size_t prg_size = prg_16k * 16384;
    size_t chr_size = chr_8k * 8192;

    if (offset + prg_size > size) return false;

    nes->prg_rom.assign(data + offset, data + offset + prg_size);
    offset += prg_size;

    if (chr_size > 0 && offset + chr_size <= size) {
        nes->chr_rom.assign(data + offset, data + offset + chr_size);
        nes->is_chr_ram = false;
    } else {
        // CHR RAM (8KB)
        nes->chr_rom.assign(8192, 0);
        nes->is_chr_ram = true;
    }

    nes_reset(nes);
    return true;
}

bool nes_load_rom_file(NesEmulator* nes, const char* filepath) {
    if (!nes || !filepath) return false;
    FILE* f = fopen(filepath, "rb");
    if (!f) return false;

    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);

    if (sz <= 16) {
        fclose(f);
        return false;
    }

    std::vector<uint8_t> buffer((size_t)sz);
    size_t read_bytes = fread(buffer.data(), 1, buffer.size(), f);
    fclose(f);

    if (read_bytes != buffer.size()) return false;
    return nes_load_rom(nes, buffer.data(), buffer.size());
}

size_t nes_run_frame(NesEmulator* nes, uint32_t controller_mask,
                     uint16_t* frame_buffer, int16_t* audio_buffer, size_t max_audio_samples) {
    if (!nes) return 0;

    nes->turbo_counter++;
    bool turbo_on = (nes->turbo_counter & 4) != 0; // ~15Hz turbo rate

    // Standard NES controller map: A, B, Select, Start, Up, Down, Left, Right
    uint8_t state = 0;
    bool btn_a = (controller_mask & NES_BTN_A) || ((controller_mask & NES_BTN_TURBO_A) && turbo_on);
    bool btn_b = (controller_mask & NES_BTN_B) || ((controller_mask & NES_BTN_TURBO_B) && turbo_on);

    if (btn_a) state |= (1 << 0);
    if (btn_b) state |= (1 << 1);
    if (controller_mask & NES_BTN_SELECT) state |= (1 << 2);
    if (controller_mask & NES_BTN_START)  state |= (1 << 3);
    if (controller_mask & NES_BTN_UP)     state |= (1 << 4);
    if (controller_mask & NES_BTN_DOWN)   state |= (1 << 5);
    if (controller_mask & NES_BTN_LEFT)   state |= (1 << 6);
    if (controller_mask & NES_BTN_RIGHT)  state |= (1 << 7);

    nes->controller_state = state;
    nes->audio_samples.clear();

    // Run until next frame begins (scanline 241 dot 0)
    int target_scanline = 241;
    bool reached_vblank = false;

    while (!reached_vblank) {
        int cpu_cycles = execute_cpu_instruction(nes);
        nes->cpu_cycles += cpu_cycles;

        for (int i = 0; i < cpu_cycles; i++) {
            nes->step_apu();
        }

        // 3 PPU dots per 1 CPU cycle
        for (int i = 0; i < cpu_cycles * 3; i++) {
            nes->step_ppu();
            if (nes->scanline == target_scanline && nes->dot == 0) {
                reached_vblank = true;
                break;
            }
        }
    }

    if (frame_buffer) {
        memcpy(frame_buffer, nes->frame_buffer, sizeof(nes->frame_buffer));
    }

    size_t samples_to_copy = std::min(nes->audio_samples.size(), max_audio_samples);
    if (audio_buffer && samples_to_copy > 0) {
        memcpy(audio_buffer, nes->audio_samples.data(), samples_to_copy * sizeof(int16_t));
    }

    return samples_to_copy;
}

uint8_t* nes_get_sram(NesEmulator* nes, size_t* out_size) {
    if (!nes || !out_size) return nullptr;
    *out_size = sizeof(nes->prg_ram);
    return nes->prg_ram;
}

bool nes_set_sram(NesEmulator* nes, const uint8_t* data, size_t size) {
    if (!nes || !data || size == 0) return false;
    size_t copy_sz = std::min(size, sizeof(nes->prg_ram));
    memcpy(nes->prg_ram, data, copy_sz);
    return true;
}

size_t nes_serialize_size(NesEmulator* nes) {
    if (!nes) return 0;
    return sizeof(nes->ram) + sizeof(nes->prg_ram) + sizeof(nes->vram) +
           sizeof(nes->palette) + sizeof(nes->oam) + 256;
}

bool nes_serialize(NesEmulator* nes, uint8_t* out_data, size_t max_size) {
    if (!nes || !out_data || max_size < nes_serialize_size(nes)) return false;
    uint8_t* ptr = out_data;

    // CPU
    memcpy(ptr, &nes->pc, 2); ptr += 2;
    *ptr++ = nes->a;
    *ptr++ = nes->x;
    *ptr++ = nes->y;
    *ptr++ = nes->sp;
    *ptr++ = nes->p;

    // Memory
    memcpy(ptr, nes->ram, sizeof(nes->ram)); ptr += sizeof(nes->ram);
    memcpy(ptr, nes->prg_ram, sizeof(nes->prg_ram)); ptr += sizeof(nes->prg_ram);
    memcpy(ptr, nes->vram, sizeof(nes->vram)); ptr += sizeof(nes->vram);
    memcpy(ptr, nes->palette, sizeof(nes->palette)); ptr += sizeof(nes->palette);
    memcpy(ptr, nes->oam, sizeof(nes->oam)); ptr += sizeof(nes->oam);

    // PPU Registers
    *ptr++ = nes->ppuctrl;
    *ptr++ = nes->ppumask;
    *ptr++ = nes->ppustatus;
    *ptr++ = nes->oam_addr;
    memcpy(ptr, &nes->v, 2); ptr += 2;
    memcpy(ptr, &nes->t, 2); ptr += 2;
    *ptr++ = nes->x_scroll;
    *ptr++ = nes->w ? 1 : 0;

    return true;
}

bool nes_unserialize(NesEmulator* nes, const uint8_t* in_data, size_t size) {
    if (!nes || !in_data || size < nes_serialize_size(nes)) return false;
    const uint8_t* ptr = in_data;

    memcpy(&nes->pc, ptr, 2); ptr += 2;
    nes->a = *ptr++;
    nes->x = *ptr++;
    nes->y = *ptr++;
    nes->sp = *ptr++;
    nes->p = *ptr++;

    memcpy(nes->ram, ptr, sizeof(nes->ram)); ptr += sizeof(nes->ram);
    memcpy(nes->prg_ram, ptr, sizeof(nes->prg_ram)); ptr += sizeof(nes->prg_ram);
    memcpy(nes->vram, ptr, sizeof(nes->vram)); ptr += sizeof(nes->vram);
    memcpy(nes->palette, ptr, sizeof(nes->palette)); ptr += sizeof(nes->palette);
    memcpy(nes->oam, ptr, sizeof(nes->oam)); ptr += sizeof(nes->oam);

    nes->ppuctrl = *ptr++;
    nes->ppumask = *ptr++;
    nes->ppustatus = *ptr++;
    nes->oam_addr = *ptr++;
    memcpy(&nes->v, ptr, 2); ptr += 2;
    memcpy(&nes->t, ptr, 2); ptr += 2;
    nes->x_scroll = *ptr++;
    nes->w = (*ptr++ != 0);

    return true;
}
