#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>
#include "libretro.h"

#define TAG "MgbaJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define GBA_WIDTH 240
#define GBA_HEIGHT 160
#define AUDIO_BUFFER_CAPACITY (32768 * 2)

// Static state
static char s_system_dir[512] = {0};
static char s_save_dir[512] = {0};
static uint16_t s_frame_buffer[GBA_WIDTH * GBA_HEIGHT];
static bool s_has_new_frame = false;
static uint32_t s_current_keys = 0;

// Audio ring buffer
static int16_t s_audio_ring[AUDIO_BUFFER_CAPACITY];
static size_t s_audio_read = 0;
static size_t s_audio_write = 0;
static bool s_core_initialized = false;
static bool s_game_loaded = false;

// ROM buffer holding in-memory ROM if loaded from bytes
static uint8_t *s_rom_copy = NULL;

static void audio_push(int16_t left, int16_t right) {
    size_t next_write = (s_audio_write + 2) % AUDIO_BUFFER_CAPACITY;
    if (next_write != s_audio_read) {
        s_audio_ring[s_audio_write] = left;
        s_audio_ring[s_audio_write + 1] = right;
        s_audio_write = next_write;
    }
}

// Libretro callbacks
static bool cb_environment(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            const int *fmt = (const int *)data;
            return (*fmt == 2); // 2 == RETRO_PIXEL_FORMAT_RGB565
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: {
            const char **dir = (const char **)data;
            *dir = s_system_dir;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: {
            const char **dir = (const char **)data;
            *dir = s_save_dir;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: {
            bool *dupe = (bool *)data;
            *dupe = true;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable *var = (struct retro_variable *)data;
            if (!var || !var->key) return false;
            // Return defaults for mGBA core variables
            if (strcmp(var->key, "mgba_solar_sensor") == 0) {
                var->value = "0";
                return true;
            }
            if (strcmp(var->key, "mgba_allow_opposing_directions") == 0) {
                var->value = "no";
                return true;
            }
            if (strcmp(var->key, "mgba_color_correction") == 0) {
                var->value = "OFF";
                return true;
            }
            if (strcmp(var->key, "mgba_frameskip") == 0) {
                var->value = "auto";
                return true;
            }
            if (strcmp(var->key, "mgba_frameskip_threshold") == 0) {
                var->value = "33";
                return true;
            }
            if (strcmp(var->key, "mgba_frameskip_interval") == 0) {
                var->value = "1";
                return true;
            }
            if (strcmp(var->key, "mgba_audio_low_pass_filter") == 0) {
                var->value = "disabled";
                return true;
            }
            if (strcmp(var->key, "mgba_interframe_blending") == 0) {
                var->value = "disabled";
                return true;
            }
            return false;
        }
        default:
            return false;
    }
}

static void cb_video_refresh(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;
    unsigned copy_w = width > GBA_WIDTH ? GBA_WIDTH : width;
    unsigned copy_h = height > GBA_HEIGHT ? GBA_HEIGHT : height;
    const uint8_t *src = (const uint8_t *)data;

    // Fast path: contiguous framebuffer copy utilizes vectorized NEON memcpy
    if (pitch == copy_w * sizeof(uint16_t)) {
        memcpy(s_frame_buffer, src, copy_w * copy_h * sizeof(uint16_t));
    } else {
        for (unsigned y = 0; y < copy_h; y++) {
            memcpy(&s_frame_buffer[y * GBA_WIDTH], src + (y * pitch), copy_w * sizeof(uint16_t));
        }
    }
    s_has_new_frame = true;
}

static void cb_audio_sample(int16_t left, int16_t right) {
    audio_push(left, right);
}

static size_t cb_audio_sample_batch(const int16_t *data, size_t frames) {
    for (size_t i = 0; i < frames; i++) {
        audio_push(data[i * 2], data[i * 2 + 1]);
    }
    return frames;
}

static void cb_input_poll(void) {
    // Input state is pushed per-frame via nativeRunFrame
}

static int16_t cb_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index;
    if (port != 0 || device != RETRO_DEVICE_JOYPAD) {
        return 0;
    }
    return (s_current_keys & (1 << id)) ? 1 : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_controlx_nativemgba_MgbaBridge_nativeInit(JNIEnv *env, jclass clazz, jstring jSystemDir, jstring jSaveDir) {
    (void)clazz;
    if (jSystemDir) {
        const char *sys = (*env)->GetStringUTFChars(env, jSystemDir, NULL);
        strncpy(s_system_dir, sys, sizeof(s_system_dir) - 1);
        (*env)->ReleaseStringUTFChars(env, jSystemDir, sys);
    }
    if (jSaveDir) {
        const char *save = (*env)->GetStringUTFChars(env, jSaveDir, NULL);
        strncpy(s_save_dir, save, sizeof(s_save_dir) - 1);
        (*env)->ReleaseStringUTFChars(env, jSaveDir, save);
    }

    if (!s_core_initialized) {
        retro_set_environment(cb_environment);
        retro_init();
        retro_set_video_refresh(cb_video_refresh);
        retro_set_audio_sample(cb_audio_sample);
        retro_set_audio_sample_batch(cb_audio_sample_batch);
        retro_set_input_poll(cb_input_poll);
        retro_set_input_state(cb_input_state);
        s_core_initialized = true;
        LOGD("mGBA core initialized successfully");
    }

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_controlx_nativemgba_MgbaBridge_nativeLoadRomBytes(JNIEnv *env, jclass clazz, jbyteArray jRomData, jint jSize, jstring jRomPath) {
    (void)clazz;
    if (s_game_loaded) {
        retro_unload_game();
        s_game_loaded = false;
    }
    if (s_rom_copy) {
        free(s_rom_copy);
        s_rom_copy = NULL;
    }

    s_rom_copy = (uint8_t *)malloc((size_t)jSize);
    if (!s_rom_copy) {
        LOGE("Failed to allocate ROM memory (%d bytes)", jSize);
        return JNI_FALSE;
    }

    (*env)->GetByteArrayRegion(env, jRomData, 0, jSize, (jbyte *)s_rom_copy);

    const char *path = NULL;
    if (jRomPath) {
        path = (*env)->GetStringUTFChars(env, jRomPath, NULL);
    }

    struct retro_game_info game;
    memset(&game, 0, sizeof(game));
    game.path = path ? path : "rom.gba";
    game.data = s_rom_copy;
    game.size = (size_t)jSize;

    bool ok = retro_load_game(&game);

    if (path) {
        (*env)->ReleaseStringUTFChars(env, jRomPath, path);
    }

    if (ok) {
        s_game_loaded = true;
        s_audio_read = 0;
        s_audio_write = 0;
        LOGD("ROM loaded successfully (%d bytes)", jSize);
        return JNI_TRUE;
    } else {
        LOGE("retro_load_game failed");
        free(s_rom_copy);
        s_rom_copy = NULL;
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_controlx_nativemgba_MgbaBridge_nativeLoadRomPath(JNIEnv *env, jclass clazz, jstring jRomPath) {
    (void)clazz;
    if (s_game_loaded) {
        retro_unload_game();
        s_game_loaded = false;
    }

    const char *path = (*env)->GetStringUTFChars(env, jRomPath, NULL);
    struct retro_game_info game;
    memset(&game, 0, sizeof(game));
    game.path = path;

    bool ok = retro_load_game(&game);
    (*env)->ReleaseStringUTFChars(env, jRomPath, path);

    if (ok) {
        s_game_loaded = true;
        s_audio_read = 0;
        s_audio_write = 0;
        LOGD("ROM file loaded successfully from path");
        return JNI_TRUE;
    } else {
        LOGE("retro_load_game failed for path");
        return JNI_FALSE;
    }
}

JNIEXPORT jint JNICALL
Java_com_controlx_nativemgba_MgbaBridge_nativeRunFrame(JNIEnv *env, jclass clazz, jint keysMask, jshortArray jAudioBuf, jint maxSamples) {
    (void)clazz;
    if (!s_game_loaded) return 0;

    s_current_keys = (uint32_t)keysMask;
    retro_run();

    if (!jAudioBuf || maxSamples <= 0) return 0;

    // Drain audio ring buffer
    size_t available = 0;
    if (s_audio_write >= s_audio_read) {
        available = s_audio_write - s_audio_read;
    } else {
        available = (AUDIO_BUFFER_CAPACITY - s_audio_read) + s_audio_write;
    }

    size_t to_read = available > (size_t)maxSamples ? (size_t)maxSamples : available;
    if (to_read == 0) return 0;

    // Copy to temporary batch if wrapping, or write directly
    jshort temp[2048];
    size_t batch = to_read > 2048 ? 2048 : to_read;

    for (size_t i = 0; i < batch; i++) {
        temp[i] = s_audio_ring[s_audio_read];
        s_audio_read = (s_audio_read + 1) % AUDIO_BUFFER_CAPACITY;
    }

    (*env)->SetShortArrayRegion(env, jAudioBuf, 0, (jsize)batch, temp);
    return (jint)batch;
}

JNIEXPORT jboolean JNICALL
Java_com_controlx_nativemgba_MgbaBridge_nativeGetVideoFrame(JNIEnv *env, jclass clazz, jobject directBuf) {
    (void)clazz;
    if (!directBuf) return JNI_FALSE;
    if (!s_has_new_frame) return JNI_FALSE;

    void *dest = (*env)->GetDirectBufferAddress(env, directBuf);
    if (!dest) return JNI_FALSE;

    memcpy(dest, s_frame_buffer, GBA_WIDTH * GBA_HEIGHT * sizeof(uint16_t));
    s_has_new_frame = false;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_controlx_nativemgba_MgbaBridge_nativeSaveState(JNIEnv *env, jclass clazz, jstring jStatePath) {
    (void)clazz;
    if (!s_game_loaded) return JNI_FALSE;

    size_t size = retro_serialize_size();
    if (size == 0) return JNI_FALSE;

    void *buffer = malloc(size);
    if (!buffer) return JNI_FALSE;

    if (!retro_serialize(buffer, size)) {
        free(buffer);
        return JNI_FALSE;
    }

    const char *path = (*env)->GetStringUTFChars(env, jStatePath, NULL);
    FILE *f = fopen(path, "wb");
    (*env)->ReleaseStringUTFChars(env, jStatePath, path);

    if (!f) {
        free(buffer);
        return JNI_FALSE;
    }

    size_t written = fwrite(buffer, 1, size, f);
    fclose(f);
    free(buffer);

    return (written == size) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_controlx_nativemgba_MgbaBridge_nativeLoadState(JNIEnv *env, jclass clazz, jstring jStatePath) {
    (void)clazz;
    if (!s_game_loaded) return JNI_FALSE;

    const char *path = (*env)->GetStringUTFChars(env, jStatePath, NULL);
    FILE *f = fopen(path, "rb");
    (*env)->ReleaseStringUTFChars(env, jStatePath, path);

    if (!f) return JNI_FALSE;

    fseek(f, 0, SEEK_END);
    long fsize = ftell(f);
    fseek(f, 0, SEEK_SET);

    if (fsize <= 0) {
        fclose(f);
        return JNI_FALSE;
    }

    void *buffer = malloc((size_t)fsize);
    if (!buffer) {
        fclose(f);
        return JNI_FALSE;
    }

    size_t read_bytes = fread(buffer, 1, (size_t)fsize, f);
    fclose(f);

    if (read_bytes != (size_t)fsize) {
        free(buffer);
        return JNI_FALSE;
    }

    bool ok = retro_unserialize(buffer, (size_t)fsize);
    free(buffer);

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_controlx_nativemgba_MgbaBridge_nativeGetSram(JNIEnv *env, jclass clazz) {
    (void)clazz;
    if (!s_game_loaded) return NULL;

    void *sram = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);

    if (!sram || size == 0) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, (jsize)size);
    if (!result) return NULL;

    (*env)->SetByteArrayRegion(env, result, 0, (jsize)size, (const jbyte *)sram);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_controlx_nativemgba_MgbaBridge_nativeSetSram(JNIEnv *env, jclass clazz, jbyteArray jSramData) {
    (void)clazz;
    if (!s_game_loaded || !jSramData) return JNI_FALSE;

    void *sram = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);

    if (!sram || size == 0) return JNI_FALSE;

    jsize data_len = (*env)->GetArrayLength(env, jSramData);
    size_t copy_len = (size_t)data_len < size ? (size_t)data_len : size;

    (*env)->GetByteArrayRegion(env, jSramData, 0, (jsize)copy_len, (jbyte *)sram);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_controlx_nativemgba_MgbaBridge_nativeReset(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (s_game_loaded) {
        retro_reset();
    }
}

JNIEXPORT void JNICALL
Java_com_controlx_nativemgba_MgbaBridge_nativeDestroy(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (s_game_loaded) {
        retro_unload_game();
        s_game_loaded = false;
    }
    if (s_core_initialized) {
        retro_deinit();
        s_core_initialized = false;
    }
    if (s_rom_copy) {
        free(s_rom_copy);
        s_rom_copy = NULL;
    }
}
