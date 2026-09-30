package com.controlx.gbatvplayer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.InputDevice
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.controlx.gbatvplayer.databinding.ActivityMainBinding
import com.controlx.gbatvplayer.rom.RomInfo
import com.controlx.gbatvplayer.rom.RomScanner
import com.controlx.gbatvplayer.ui.RomCardAdapter
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_SETTINGS = "gba_tv_settings"
        private const val KEY_CUSTOM_DIR = "custom_rom_dir"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: RomCardAdapter
    private var romList = listOf<RomInfo>()
    private var currentRomDir: File? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        loadRoms()
    }

    private val browserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val folderPath = result.data?.getStringExtra(FileBrowserActivity.EXTRA_SELECTED_FOLDER)
            if (folderPath != null) {
                getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CUSTOM_DIR, folderPath)
                    .apply()
            }
            loadRoms()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupRecyclerView()
            setupListeners()
            checkPermissionsAndLoad()
            detectControllers()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
        }
    }

    private fun setupRecyclerView() {
        adapter = RomCardAdapter(emptyList()) { rom ->
            launchEmulator(rom)
        }
        // 3 columns on standard 720p / 1080p TV layout
        binding.recyclerRoms.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerRoms.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnRefresh.setOnClickListener {
            loadRoms()
            Toast.makeText(this, "Library refreshed", Toast.LENGTH_SHORT).show()
        }

        binding.btnRemap.setOnClickListener {
            startActivity(Intent(this, RemapActivity::class.java))
        }

        binding.btnFolder.setOnClickListener {
            openFileBrowser()
        }

        binding.btnEmptyBrowse.setOnClickListener {
            openFileBrowser()
        }

        binding.btnEmptyRefresh.setOnClickListener {
            loadRoms()
            Toast.makeText(this, "Rescanning folder...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFileBrowser() {
        val intent = Intent(this, FileBrowserActivity::class.java).apply {
            currentRomDir?.let {
                putExtra(FileBrowserActivity.EXTRA_SELECTED_FOLDER, it.absolutePath)
            }
        }
        browserLauncher.launch(intent)
    }

    private fun checkPermissionsAndLoad() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                val missing = mutableListOf<String>()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                if (missing.isNotEmpty()) {
                    permissionLauncher.launch(missing.toTypedArray())
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Permission check error", e)
        }

        loadRoms()
    }

    private fun loadRoms() {
        val prefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val customPath = prefs.getString(KEY_CUSTOM_DIR, null)

        val targetDir = if (customPath != null && File(customPath).exists()) {
            File(customPath)
        } else {
            RomScanner.getDefaultRomDir(this)
        }
        currentRomDir = targetDir

        binding.tvCurrentDir.text = getString(R.string.folder_label, targetDir.absolutePath)

        Thread {
            try {
                val list = RomScanner.scanDirectory(targetDir)
                runOnUiThread {
                    romList = list
                    adapter.updateList(list)
                    if (list.isEmpty()) {
                        binding.emptyStateView.visibility = View.VISIBLE
                        binding.recyclerRoms.visibility = View.GONE
                        binding.tvRomCount.text = "0 Games Found"
                        binding.tvEmptyDesc.text = getString(R.string.no_roms_desc, targetDir.absolutePath)
                        binding.btnEmptyBrowse.requestFocus()
                    } else {
                        binding.emptyStateView.visibility = View.GONE
                        binding.recyclerRoms.visibility = View.VISIBLE
                        binding.tvRomCount.text = getString(R.string.roms_count, list.size)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning ROMs", e)
            }
        }.start()
    }

    private fun launchEmulator(rom: RomInfo) {
        val intent = Intent(this, EmulatorActivity::class.java).apply {
            putExtra(EmulatorActivity.EXTRA_ROM_PATH, rom.filePath)
            putExtra(EmulatorActivity.EXTRA_ROM_TITLE, rom.title)
            putExtra(EmulatorActivity.EXTRA_ROM_IS_ZIP, rom.isZip)
            putExtra(EmulatorActivity.EXTRA_ROM_ZIP_ENTRY, rom.zipEntryName)
            putExtra(EmulatorActivity.EXTRA_CONSOLE_TYPE, rom.consoleType.name)
        }
        startActivity(intent)
    }

    private fun detectControllers() {
        try {
            val deviceIds = InputDevice.getDeviceIds()
            var hasGamepad = false
            var pocketPadName: String? = null

            for (id in deviceIds) {
                val device = InputDevice.getDevice(id) ?: continue
                val sources = device.sources
                if ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                ) {
                    hasGamepad = true
                    val name = device.name ?: ""
                    if (name.contains("PocketPad", ignoreCase = true)) {
                        pocketPadName = name
                    }
                }
            }

            if (pocketPadName != null) {
                binding.tvControllerStatus.visibility = View.VISIBLE
                binding.tvControllerStatus.text = getString(R.string.controller_pocketpad, pocketPadName)
            } else if (hasGamepad) {
                binding.tvControllerStatus.visibility = View.VISIBLE
                binding.tvControllerStatus.text = getString(R.string.controller_connected, "Standard Gamepad")
            } else {
                binding.tvControllerStatus.visibility = View.VISIBLE
                binding.tvControllerStatus.text = getString(R.string.no_gamepad_warning)
                binding.tvControllerStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting controllers", e)
        }
    }

    override fun onResume() {
        super.onResume()
        detectControllers()
    }
}
