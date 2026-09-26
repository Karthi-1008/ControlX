package com.controlx.gbatvplayer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log

class GbaAudioPlayer(private val sampleRate: Int = 32768) {

    companion object {
        private const val TAG = "GbaAudioPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    fun start() {
        if (isPlaying) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // Buffer for ~60-80ms cushion to absorb low-end TV scheduling jitter
        val targetBufferSize = (minBufferSize * 3).coerceAtLeast(4096)

        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

            val builder = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(targetBufferSize)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            audioTrack = builder.build()
            audioTrack?.play()
            isPlaying = true
            Log.d(TAG, "AudioTrack started at $sampleRate Hz, buffer=$targetBufferSize bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioTrack", e)
        }
    }

    fun write(samples: ShortArray, offset: Int, count: Int): Int {
        val track = audioTrack ?: return 0
        if (!isPlaying || count <= 0) return 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            track.write(samples, offset, count, AudioTrack.WRITE_NON_BLOCKING)
        } else {
            track.write(samples, offset, count)
        }
    }

    fun pause() {
        if (!isPlaying) return
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing AudioTrack", e)
        }
        isPlaying = false
    }

    fun resume() {
        if (isPlaying) return
        try {
            audioTrack?.play()
            isPlaying = true
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming AudioTrack", e)
        }
    }

    fun release() {
        isPlaying = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack", e)
        }
        audioTrack = null
    }
}
