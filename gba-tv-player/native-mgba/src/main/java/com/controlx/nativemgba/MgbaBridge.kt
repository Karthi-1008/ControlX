package com.controlx.nativemgba

import java.nio.ByteBuffer

object MgbaBridge {
    init {
        try {
            System.loadLibrary("mgba-jni")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    // Standard RetroPad / GBA button bitmask constants
    const val KEY_B = 1 shl 0
    const val KEY_Y = 1 shl 1
    const val KEY_SELECT = 1 shl 2
    const val KEY_START = 1 shl 3
    const val KEY_UP = 1 shl 4
    const val KEY_DOWN = 1 shl 5
    const val KEY_LEFT = 1 shl 6
    const val KEY_RIGHT = 1 shl 7
    const val KEY_A = 1 shl 8
    const val KEY_X = 1 shl 9
    const val KEY_L = 1 shl 10
    const val KEY_R = 1 shl 11

    // Video specs
    const val GBA_WIDTH = 240
    const val GBA_HEIGHT = 160
    const val GBA_FRAME_SIZE = GBA_WIDTH * GBA_HEIGHT * 2 // RGB565 = 2 bytes per pixel
    const val GBA_FPS = 59.7275f
    const val AUDIO_SAMPLE_RATE = 32768 // mGBA native audio sample rate

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
