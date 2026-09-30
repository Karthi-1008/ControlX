package com.controlx.nativenes

import java.nio.ByteBuffer

object NesBridge {
    init {
        try {
            System.loadLibrary("nes-jni")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    // NES / RetroPad button bitmask constants
    const val KEY_B = 1 shl 0
    const val KEY_TURBO_B = 1 shl 1
    const val KEY_SELECT = 1 shl 2
    const val KEY_START = 1 shl 3
    const val KEY_UP = 1 shl 4
    const val KEY_DOWN = 1 shl 5
    const val KEY_LEFT = 1 shl 6
    const val KEY_RIGHT = 1 shl 7
    const val KEY_A = 1 shl 8
    const val KEY_TURBO_A = 1 shl 9

    // Video specs
    const val NES_WIDTH = 256
    const val NES_HEIGHT = 240
    const val NES_FRAME_SIZE = NES_WIDTH * NES_HEIGHT * 2 // RGB565 = 2 bytes per pixel (122,880 bytes)
    const val NES_FPS = 60.0988f
    const val AUDIO_SAMPLE_RATE = 44100 // Standard 44.1 kHz stereo audio

    @JvmStatic
    external fun nativeInit(systemDir: String, saveDir: String): Boolean

    @JvmStatic
    external fun nativeLoadRomBytes(romData: ByteArray, size: Int, romPath: String): Boolean

    @JvmStatic
    external fun nativeLoadRomPath(romPath: String): Boolean

    @JvmStatic
    external fun nativeRunFrame(keysMask: Int, audioBuffer: ShortArray, maxSamples: Int): Int

    @JvmStatic
    external fun nativeGetVideoFrame(outDirectBuffer: ByteBuffer): Boolean

    @JvmStatic
    external fun nativeSaveState(statePath: String): Boolean

    @JvmStatic
    external fun nativeLoadState(statePath: String): Boolean

    @JvmStatic
    external fun nativeGetSram(): ByteArray?

    @JvmStatic
    external fun nativeSetSram(sramData: ByteArray): Boolean

    @JvmStatic
    external fun nativeReset()

    @JvmStatic
    external fun nativeDestroy()
}
