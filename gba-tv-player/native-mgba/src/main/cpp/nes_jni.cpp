#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>
#include "nes_core.h"

#define TAG "NesJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static NesEmulator* s_nes = NULL;
static char s_system_dir[512] = {0};
static char s_save_dir[512] = {0};
static uint16_t s_nes_frame_buffer[NES_SCREEN_WIDTH * NES_SCREEN_HEIGHT];
static bool s_has_new_frame = false;
static bool s_game_loaded = false;

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jboolean JNICALL
Java_com_controlx_nativenes_NesBridge_nativeInit(JNIEnv *env, jclass clazz, jstring jSystemDir, jstring jSaveDir) {
    (void)clazz;
    if (jSystemDir) {
        const char *sys = env->GetStringUTFChars(jSystemDir, NULL);
        strncpy(s_system_dir, sys, sizeof(s_system_dir) - 1);
        env->ReleaseStringUTFChars(jSystemDir, sys);
    }
    if (jSaveDir) {
        const char *save = env->GetStringUTFChars(jSaveDir, NULL);
        strncpy(s_save_dir, save, sizeof(s_save_dir) - 1);
        env->ReleaseStringUTFChars(jSaveDir, save);
    }

    if (!s_nes) {
        s_nes = nes_create();
        LOGD("NES core created successfully");
    }

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_controlx_nativenes_NesBridge_nativeLoadRomBytes(JNIEnv *env, jclass clazz, jbyteArray jRomData, jint jSize, jstring jRomPath) {
    (void)clazz;
    (void)jRomPath;
    if (!s_nes) {
        s_nes = nes_create();
    }

    jbyte* bytes = env->GetByteArrayElements(jRomData, NULL);
    if (!bytes) {
        LOGE("Failed to get ROM bytes from Java");
        return JNI_FALSE;
    }

    bool ok = nes_load_rom(s_nes, (const uint8_t*)bytes, (size_t)jSize);
    env->ReleaseByteArrayElements(jRomData, bytes, JNI_ABORT);

    if (ok) {
        s_game_loaded = true;
        s_has_new_frame = false;
        LOGD("NES ROM loaded successfully from bytes (%d bytes)", jSize);
        return JNI_TRUE;
    } else {
        LOGE("Failed to parse and load NES ROM from bytes");
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_controlx_nativenes_NesBridge_nativeLoadRomPath(JNIEnv *env, jclass clazz, jstring jRomPath) {
    (void)clazz;
    if (!s_nes) {
        s_nes = nes_create();
    }

    const char *path = env->GetStringUTFChars(jRomPath, NULL);
    bool ok = nes_load_rom_file(s_nes, path);
    env->ReleaseStringUTFChars(jRomPath, path);

    if (ok) {
        s_game_loaded = true;
        s_has_new_frame = false;
        LOGD("NES ROM loaded successfully from path");
        return JNI_TRUE;
    } else {
        LOGE("Failed to load NES ROM file");
        return JNI_FALSE;
    }
}

JNIEXPORT jint JNICALL
Java_com_controlx_nativenes_NesBridge_nativeRunFrame(JNIEnv *env, jclass clazz, jint keysMask, jshortArray jAudioBuf, jint maxSamples) {
    (void)clazz;
    if (!s_nes || !s_game_loaded) return 0;

    int16_t tempAudio[2048];
    size_t maxAudio = (maxSamples > 2048) ? 2048 : (size_t)maxSamples;

    size_t samples = nes_run_frame(s_nes, (uint32_t)keysMask, s_nes_frame_buffer, tempAudio, maxAudio);
    s_has_new_frame = true;

    if (jAudioBuf && samples > 0) {
        env->SetShortArrayRegion(jAudioBuf, 0, (jsize)samples, (const jshort*)tempAudio);
    }

    return (jint)samples;
}

JNIEXPORT jboolean JNICALL
Java_com_controlx_nativenes_NesBridge_nativeGetVideoFrame(JNIEnv *env, jclass clazz, jobject directBuf) {
    (void)clazz;
    if (!directBuf || !s_has_new_frame) return JNI_FALSE;

    void *dest = env->GetDirectBufferAddress(directBuf);
    if (!dest) return JNI_FALSE;

    memcpy(dest, s_nes_frame_buffer, NES_SCREEN_WIDTH * NES_SCREEN_HEIGHT * sizeof(uint16_t));
    s_has_new_frame = false;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_controlx_nativenes_NesBridge_nativeSaveState(JNIEnv *env, jclass clazz, jstring jStatePath) {
    (void)clazz;
    if (!s_nes || !s_game_loaded) return JNI_FALSE;

    size_t sz = nes_serialize_size(s_nes);
    if (sz == 0) return JNI_FALSE;

    uint8_t *buf = (uint8_t*)malloc(sz);
    if (!buf) return JNI_FALSE;

    if (!nes_serialize(s_nes, buf, sz)) {
        free(buf);
        return JNI_FALSE;
    }

    const char *path = env->GetStringUTFChars(jStatePath, NULL);
    FILE *f = fopen(path, "wb");
    env->ReleaseStringUTFChars(jStatePath, path);

    if (!f) {
        free(buf);
        return JNI_FALSE;
    }

    size_t written = fwrite(buf, 1, sz, f);
    fclose(f);
    free(buf);

    return (written == sz) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_controlx_nativenes_NesBridge_nativeLoadState(JNIEnv *env, jclass clazz, jstring jStatePath) {
    (void)clazz;
    if (!s_nes || !s_game_loaded) return JNI_FALSE;

    const char *path = env->GetStringUTFChars(jStatePath, NULL);
    FILE *f = fopen(path, "rb");
    env->ReleaseStringUTFChars(jStatePath, path);

    if (!f) return JNI_FALSE;

    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);

    if (sz <= 0) {
        fclose(f);
        return JNI_FALSE;
    }

    uint8_t *buf = (uint8_t*)malloc((size_t)sz);
    if (!buf) {
        fclose(f);
        return JNI_FALSE;
    }

    size_t read_bytes = fread(buf, 1, (size_t)sz, f);
    fclose(f);

    if (read_bytes != (size_t)sz) {
        free(buf);
        return JNI_FALSE;
    }

    bool ok = nes_unserialize(s_nes, buf, (size_t)sz);
    free(buf);

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_controlx_nativenes_NesBridge_nativeGetSram(JNIEnv *env, jclass clazz) {
    (void)clazz;
    if (!s_nes || !s_game_loaded) return NULL;

    size_t sz = 0;
    uint8_t *sram = nes_get_sram(s_nes, &sz);
    if (!sram || sz == 0) return NULL;

    jbyteArray res = env->NewByteArray((jsize)sz);
    if (!res) return NULL;

    env->SetByteArrayRegion(res, 0, (jsize)sz, (const jbyte*)sram);
    return res;
}

JNIEXPORT jboolean JNICALL
Java_com_controlx_nativenes_NesBridge_nativeSetSram(JNIEnv *env, jclass clazz, jbyteArray jSramData) {
    (void)clazz;
    if (!s_nes || !s_game_loaded || !jSramData) return JNI_FALSE;

    jsize len = env->GetArrayLength(jSramData);
    jbyte *bytes = env->GetByteArrayElements(jSramData, NULL);
    if (!bytes) return JNI_FALSE;

    bool ok = nes_set_sram(s_nes, (const uint8_t*)bytes, (size_t)len);
    env->ReleaseByteArrayElements(jSramData, bytes, JNI_ABORT);

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_controlx_nativenes_NesBridge_nativeReset(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (s_nes && s_game_loaded) {
        nes_reset(s_nes);
    }
}

JNIEXPORT void JNICALL
Java_com_controlx_nativenes_NesBridge_nativeDestroy(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    s_game_loaded = false;
    s_has_new_frame = false;
    if (s_nes) {
        nes_destroy(s_nes);
        s_nes = NULL;
    }
}

#ifdef __cplusplus
}
#endif
