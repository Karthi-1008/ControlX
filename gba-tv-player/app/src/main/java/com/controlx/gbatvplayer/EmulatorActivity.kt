package com.controlx.gbatvplayer

import android.content.Context
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.controlx.gbatvplayer.audio.GbaAudioPlayer
import com.controlx.gbatvplayer.databinding.ActivityEmulatorBinding
import com.controlx.gbatvplayer.databinding.DialogPauseMenuBinding
import com.controlx.gbatvplayer.input.InputManager
import com.controlx.gbatvplayer.render.GbaGlRenderer
import com.controlx.gbatvplayer.rom.ConsoleType
import com.controlx.gbatvplayer.rom.RomInfo
import com.controlx.gbatvplayer.rom.RomScanner
import com.controlx.nativemgba.MgbaBridge
import com.controlx.nativenes.NesBridge
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

class EmulatorActivity : AppCompatActivity(), InputManager.PocketPadListener {

    companion object {
        private const val TAG = "EmulatorActivity"
        const val EXTRA_ROM_PATH = "extra_rom_path"
        const val EXTRA_ROM_TITLE = "extra_rom_title"
        const val EXTRA_ROM_IS_ZIP = "extra_rom_is_zip"
        const val EXTRA_ROM_ZIP_ENTRY = "extra_rom_zip_entry"
        const val EXTRA_CONSOLE_TYPE = "extra_console_type"

        // Native frame durations in nanoseconds:
        // GBA: 1.0 / 59.7275 s = ~16,742,706 ns
        private const val GBA_FRAME_DURATION_NS = 16_742_706L
        // NES: 1.0 / 60.0988 s = ~16,639,263 ns
        private const val NES_FRAME_DURATION_NS = 16_639_263L
    }

    private lateinit var binding: ActivityEmulatorBinding
    private lateinit var glRenderer: GbaGlRenderer
    private lateinit var audioPlayer: GbaAudioPlayer
    private lateinit var inputManager: InputManager

    private var romPath: String = ""
    private var romTitle: String = ""
    private var isZip: Boolean = false
    private var zipEntryName: String? = null
    private var consoleType: ConsoleType = ConsoleType.GBA

    private lateinit var saveDir: File
    private var currentSlot: Int = 1
    private var isFastForward = false
    private var isBilinear = false

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var emulationThread: Thread? = null

    // Direct byte buffers large enough for either GBA (76,800 B) or NES (122,880 B)
    private val maxFrameSize = maxOf(MgbaBridge.GBA_FRAME_SIZE, NesBridge.NES_FRAME_SIZE)
    private val directFrameBufferA: ByteBuffer = ByteBuffer.allocateDirect(maxFrameSize)
        .order(ByteOrder.nativeOrder())
    private val directFrameBufferB: ByteBuffer = ByteBuffer.allocateDirect(maxFrameSize)
        .order(ByteOrder.nativeOrder())

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pauseDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmulatorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI()

        romPath = intent.getStringExtra(EXTRA_ROM_PATH) ?: ""
        romTitle = intent.getStringExtra(EXTRA_ROM_TITLE) ?: "Game"
        isZip = intent.getBooleanExtra(EXTRA_ROM_IS_ZIP, false)
        zipEntryName = intent.getStringExtra(EXTRA_ROM_ZIP_ENTRY)

        val consoleTypeStr = intent.getStringExtra(EXTRA_CONSOLE_TYPE)
        consoleType = if (consoleTypeStr != null) {
            try {
                ConsoleType.valueOf(consoleTypeStr.uppercase())
            } catch (e: Exception) {
                detectConsoleType()
            }
        } else {
            detectConsoleType()
        }

        if (romPath.isBlank()) {
            Toast.makeText(this, "ROM path missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        saveDir = File(getExternalFilesDir(null) ?: filesDir, "saves").apply { mkdirs() }

        inputManager = InputManager(this)
        inputManager.pocketPadListener = this

        val sampleRate = if (consoleType == ConsoleType.NES) NesBridge.AUDIO_SAMPLE_RATE else MgbaBridge.AUDIO_SAMPLE_RATE
        audioPlayer = GbaAudioPlayer(sampleRate = sampleRate)

        glRenderer = GbaGlRenderer()
        if (consoleType == ConsoleType.NES) {
            glRenderer.configure(NesBridge.NES_WIDTH, NesBridge.NES_HEIGHT, 4.0f, 3.0f)
        } else {
            glRenderer.configure(MgbaBridge.GBA_WIDTH, MgbaBridge.GBA_HEIGHT, 3.0f, 2.0f)
        }

        binding.glSurfaceView.setEGLContextClientVersion(2)
        binding.glSurfaceView.setRenderer(glRenderer)
        binding.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        startEmulation()
    }

    private fun detectConsoleType(): ConsoleType {
        val lowerPath = romPath.lowercase()
        val lowerEntry = zipEntryName?.lowercase() ?: ""
        return if (lowerPath.endsWith(".nes") || lowerEntry.endsWith(".nes")) {
            ConsoleType.NES
        } else {
            ConsoleType.GBA
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun startEmulation() {
        isRunning.set(true)
        isPaused.set(false)
        audioPlayer.start()

        val threadName = if (consoleType == ConsoleType.NES) "NesEmulationThread" else "GbaEmulationThread"
        emulationThread = Thread({
            runEmulationLoop()
        }, threadName).apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun runEmulationLoop() {
        val systemDir = File(filesDir, "system").apply { mkdirs() }.absolutePath
        val savesDirPath = saveDir.absolutePath

        val isNes = (consoleType == ConsoleType.NES)
        if (isNes) {
            NesBridge.nativeInit(systemDir, savesDirPath)
        } else {
            MgbaBridge.nativeInit(systemDir, savesDirPath)
        }

        // Stream zip directly to file to prevent allocating large byte array on 1GB RAM Java heap
        val loaded = if (isZip && zipEntryName != null) {
            try {
                val cacheRom = RomScanner.extractZipRomToCache(this, romPath, zipEntryName!!)
                if (isNes) NesBridge.nativeLoadRomPath(cacheRom.absolutePath) else MgbaBridge.nativeLoadRomPath(cacheRom.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting zip ROM to cache", e)
                false
            }
        } else {
            if (isNes) NesBridge.nativeLoadRomPath(romPath) else MgbaBridge.nativeLoadRomPath(romPath)
        }

        if (!loaded) {
            mainHandler.post {
                val coreName = if (isNes) "NES core" else "mGBA core"
                Toast.makeText(this, "Failed to load ROM in $coreName", Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }

        // Load existing SRAM battery save if present
        loadBatterySram()

        val audioBuffer = ShortArray(2048)
        var currentFrameBuffer = directFrameBufferA
        var nextFrameTargetNs = System.nanoTime()
        var skippedFramesCount = 0
        val maxConsecutiveSkips = 2
        val baseFrameDurationNs = if (isNes) NES_FRAME_DURATION_NS else GBA_FRAME_DURATION_NS

        var fpsCounter = 0
        var lastFpsTime = System.currentTimeMillis()
        var lastSramSaveTime = System.currentTimeMillis()

        while (isRunning.get()) {
            if (isPaused.get()) {
                LockSupport.parkNanos(20_000_000L) // 20ms
                nextFrameTargetNs = System.nanoTime()
                continue
            }

            val keys = inputManager.keysMask
            val nowNs = System.nanoTime()

            // If behind schedule by >12ms, skip video rendering for up to 2 frames to maintain 60FPS audio and physics
            val isBehind = (nowNs - nextFrameTargetNs) > 12_000_000L
            val shouldSkipRender = isBehind && (skippedFramesCount < maxConsecutiveSkips)

            // Step frame in native core
            val samplesCount = if (isNes) {
                NesBridge.nativeRunFrame(keys, audioBuffer, audioBuffer.size)
            } else {
                MgbaBridge.nativeRunFrame(keys, audioBuffer, audioBuffer.size)
            }

            // Submit audio immediately
            if (samplesCount > 0) {
                audioPlayer.write(audioBuffer, 0, samplesCount)
            }

            if (shouldSkipRender) {
                skippedFramesCount++
            } else {
                skippedFramesCount = 0
                val frameOk = if (isNes) {
                    NesBridge.nativeGetVideoFrame(currentFrameBuffer)
                } else {
                    MgbaBridge.nativeGetVideoFrame(currentFrameBuffer)
                }

                if (frameOk) {
                    glRenderer.submitFrame(currentFrameBuffer)
                    binding.glSurfaceView.requestRender()
                    // Swap double buffers to eliminate tearing and thread contention
                    currentFrameBuffer = if (currentFrameBuffer === directFrameBufferA) directFrameBufferB else directFrameBufferA
                }
            }

            fpsCounter++
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastFpsTime >= 1000) {
                val fps = fpsCounter
                fpsCounter = 0
                lastFpsTime = nowMs
                mainHandler.post {
                    val speedText = if (isFastForward) " [2x FastForward]" else ""
                    val consoleTag = if (isNes) "[NES] " else "[GBA] "
                    binding.tvFpsIndicator.text = "$consoleTag$fps FPS$speedText"
                }
            }

            // Periodic auto-save battery SRAM every 15 seconds
            if (nowMs - lastSramSaveTime >= 15_000) {
                saveBatterySram()
                lastSramSaveTime = nowMs
            }

            // Cadence-anchored frame rate limiter
            val targetInterval = if (isFastForward) baseFrameDurationNs / 2 else baseFrameDurationNs
            nextFrameTargetNs += targetInterval

            val afterWorkNs = System.nanoTime()
            val sleepNs = nextFrameTargetNs - afterWorkNs

            if (sleepNs > 2_000_000L) {
                LockSupport.parkNanos(sleepNs - 1_000_000L)
            }

            // Reset deadline if lagged behind by > 100ms (e.g. system interrupt / GC)
            if (afterWorkNs - nextFrameTargetNs > 100_000_000L) {
                nextFrameTargetNs = afterWorkNs
            }
        }

        // Save SRAM on exit
        saveBatterySram()
        if (isNes) {
            NesBridge.nativeDestroy()
        } else {
            MgbaBridge.nativeDestroy()
        }
    }

    private fun getSramFile(): File {
        val safeName = romTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(saveDir, "$safeName.sav")
    }

    private fun getStateFile(slot: Int): File {
        val safeName = romTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(saveDir, "$safeName.state$slot")
    }

    private fun loadBatterySram() {
        val sramFile = getSramFile()
        if (sramFile.exists() && sramFile.length() > 0) {
            try {
                val bytes = sramFile.readBytes()
                if (consoleType == ConsoleType.NES) {
                    NesBridge.nativeSetSram(bytes)
                } else {
                    MgbaBridge.nativeSetSram(bytes)
                }
                Log.d(TAG, "Loaded battery SRAM save (${bytes.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading SRAM", e)
            }
        }
    }

    private fun saveBatterySram() {
        try {
            val sram = if (consoleType == ConsoleType.NES) {
                NesBridge.nativeGetSram()
            } else {
                MgbaBridge.nativeGetSram()
            }
            if (sram != null && sram.isNotEmpty()) {
                getSramFile().writeBytes(sram)
                Log.d(TAG, "Saved battery SRAM (${sram.size} bytes)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving SRAM", e)
        }
    }

    fun showPauseMenu() {
        if (pauseDialog?.isShowing == true) return
        isPaused.set(true)
        audioPlayer.pause()

        val menuBinding = DialogPauseMenuBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(menuBinding.root)
            .setCancelable(true)
            .setOnDismissListener {
                isPaused.set(false)
                audioPlayer.resume()
                hideSystemUI()
            }
            .create()

        pauseDialog = dialog

        fun updateMenuState() {
            menuBinding.btnMenuSlot.text = "Slot: $currentSlot (Tap to switch 1-4)"
            menuBinding.btnMenuSave.text = "Save State (Slot $currentSlot)"
            menuBinding.btnMenuLoad.text = "Load State (Slot $currentSlot)"
            menuBinding.btnMenuFastForward.text = "Fast Forward: ${if (isFastForward) "2x" else "Off"}"
            menuBinding.btnMenuFilter.text = "Filter: ${if (isBilinear) "Bilinear" else "Nearest-Neighbor"}"
        }

        updateMenuState()

        menuBinding.btnMenuResume.setOnClickListener {
            dialog.dismiss()
        }

        menuBinding.btnMenuSlot.setOnClickListener {
            currentSlot = (currentSlot % 4) + 1
            updateMenuState()
        }

        menuBinding.btnMenuSave.setOnClickListener {
            val stateFile = getStateFile(currentSlot)
            val ok = if (consoleType == ConsoleType.NES) {
                NesBridge.nativeSaveState(stateFile.absolutePath)
            } else {
                MgbaBridge.nativeSaveState(stateFile.absolutePath)
            }
            Toast.makeText(this, if (ok) getString(R.string.state_saved, currentSlot) else getString(R.string.state_error), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        menuBinding.btnMenuLoad.setOnClickListener {
            val stateFile = getStateFile(currentSlot)
            if (!stateFile.exists()) {
                Toast.makeText(this, getString(R.string.state_not_found, currentSlot), Toast.LENGTH_SHORT).show()
            } else {
                val ok = if (consoleType == ConsoleType.NES) {
                    NesBridge.nativeLoadState(stateFile.absolutePath)
                } else {
                    MgbaBridge.nativeLoadState(stateFile.absolutePath)
                }
                Toast.makeText(this, if (ok) getString(R.string.state_loaded, currentSlot) else getString(R.string.state_error), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        menuBinding.btnMenuFastForward.setOnClickListener {
            isFastForward = !isFastForward
            updateMenuState()
        }

        menuBinding.btnMenuFilter.setOnClickListener {
            isBilinear = !isBilinear
            glRenderer.isBilinear = isBilinear
            updateMenuState()
        }

        menuBinding.btnMenuReset.setOnClickListener {
            if (consoleType == ConsoleType.NES) {
                NesBridge.nativeReset()
            } else {
                MgbaBridge.nativeReset()
            }
            Toast.makeText(this, "Game reset", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        menuBinding.btnMenuExit.setOnClickListener {
            dialog.dismiss()
            finish()
        }

        dialog.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK) {
            showPauseMenu()
            return true
        }
        if (inputManager.onKeyDown(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (inputManager.onKeyUp(keyCode, event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (inputManager.onGenericMotionEvent(event)) {
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onPocketPadDetected(deviceName: String) {
        mainHandler.post {
            binding.tvHudNotification.visibility = View.VISIBLE
            binding.tvHudNotification.text = getString(R.string.controller_pocketpad, deviceName)
            mainHandler.postDelayed({
                binding.tvHudNotification.visibility = View.GONE
            }, 3500)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        if (!isPaused.get()) {
            audioPlayer.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        audioPlayer.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        isPaused.set(false)
        try {
            emulationThread?.join(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        audioPlayer.release()
    }
}
