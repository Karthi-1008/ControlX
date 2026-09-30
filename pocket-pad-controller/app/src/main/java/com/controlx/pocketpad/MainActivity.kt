package com.controlx.pocketpad

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.controlx.pocketpad.databinding.ActivityMainBinding
import com.controlx.pocketpad.databinding.DialogDeviceListBinding
import com.controlx.pocketpad.hid.BluetoothHidManager
import com.controlx.pocketpad.ui.DeviceListAdapter
import com.controlx.pocketpad.ui.GamepadMode
import com.controlx.pocketpad.ui.TouchControllerView

class MainActivity : AppCompatActivity(), BluetoothHidManager.Listener, TouchControllerView.InputChangeListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var hidManager: BluetoothHidManager

    private var isHapticsEnabled = false
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private var isScreenDimmed = false

    private val dimRunnable = Runnable {
        dimScreen(true)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            hidManager.init()
            updateUi()
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val PREFS_NAME = "pocketpad_prefs"
        private const val KEY_GAMEPAD_MODE = "key_gamepad_mode"
    }

    private var currentMode = GamepadMode.GBA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Immersive full-screen
        hideSystemUI()

        hidManager = BluetoothHidManager(this)
        hidManager.listener = this

        binding.touchController.inputChangeListener = this

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMode = prefs.getString(KEY_GAMEPAD_MODE, GamepadMode.GBA.name) ?: GamepadMode.GBA.name
        currentMode = try {
            GamepadMode.valueOf(savedMode)
        } catch (e: Exception) {
            GamepadMode.GBA
        }
        binding.touchController.gamepadMode = currentMode
        updatePadModeButton()

        binding.btnPadMode.setOnClickListener {
            currentMode = if (currentMode == GamepadMode.GBA) GamepadMode.NES else GamepadMode.GBA
            binding.touchController.gamepadMode = currentMode
            prefs.edit().putString(KEY_GAMEPAD_MODE, currentMode.name).apply()
            updatePadModeButton()
            Toast.makeText(this, "Gamepad switched to ${currentMode.name} mode", Toast.LENGTH_SHORT).show()
        }

        binding.btnConnect.setOnClickListener {
            if (hidManager.isConnected) {
                hidManager.disconnect()
            } else {
                showDeviceSelectionDialog()
            }
        }

        binding.btnReconnect.setOnClickListener {
            hidManager.reconnectLastDevice()
        }

        binding.btnHaptics.setOnClickListener {
            isHapticsEnabled = !isHapticsEnabled
            binding.touchController.isHapticsEnabled = isHapticsEnabled
            binding.btnHaptics.text = getString(R.string.btn_haptic_toggle, if (isHapticsEnabled) "On" else "Off")
        }

        checkAndRequestPermissions()
        resetInactivityTimer()
    }

    private fun updatePadModeButton() {
        if (currentMode == GamepadMode.NES) {
            binding.btnPadMode.text = "🕹️ Pad: NES"
            binding.btnPadMode.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        } else {
            binding.btnPadMode.text = "🎮 Pad: GBA"
            binding.btnPadMode.setTextColor(ContextCompat.getColor(this, R.color.gba_indigo))
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            hidManager.init()
            updateUi()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectionDialog() {
        val dialogBinding = DialogDeviceListBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        val pairedDevices = hidManager.getPairedDevices()
        if (pairedDevices.isEmpty()) {
            dialogBinding.emptyHint.visibility = View.VISIBLE
            dialogBinding.recyclerDevices.visibility = View.GONE
        } else {
            dialogBinding.emptyHint.visibility = View.GONE
            dialogBinding.recyclerDevices.visibility = View.VISIBLE
            dialogBinding.recyclerDevices.layoutManager = LinearLayoutManager(this)
            dialogBinding.recyclerDevices.adapter = DeviceListAdapter(pairedDevices) { device ->
                dialog.dismiss()
                hidManager.connect(device)
            }
        }

        dialogBinding.btnMakeDiscoverable.setOnClickListener {
            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
            startActivity(discoverableIntent)
            Toast.makeText(this, "Phone is now discoverable for 300 seconds. Search for accessories on your TV!", Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateUi() {
        runOnUiThread {
            if (hidManager.isConnected) {
                val name = hidManager.connectedDevice?.name ?: "TV"
                binding.statusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green))
                binding.statusText.text = getString(R.string.status_connected, name)
                binding.btnConnect.text = getString(R.string.btn_disconnect)
                binding.btnReconnect.visibility = View.GONE
            } else {
                binding.statusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red))
                binding.statusText.text = getString(R.string.status_disconnected)
                binding.btnConnect.text = getString(R.string.btn_connect)
                val lastAddress = hidManager.getLastDeviceAddress()
                binding.btnReconnect.visibility = if (lastAddress != null) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onRegistrationStateChanged(registered: Boolean) {
        runOnUiThread {
            if (!registered) {
                binding.statusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_amber))
                binding.statusText.text = getString(R.string.status_registering)
            } else if (!hidManager.isConnected) {
                binding.statusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_amber))
                binding.statusText.text = getString(R.string.status_ready)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
        runOnUiThread {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    updateUi()
                    Toast.makeText(this, "Connected to ${device?.name ?: "TV"}", Toast.LENGTH_SHORT).show()
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    binding.statusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_amber))
                    binding.statusText.text = getString(R.string.status_connecting, device?.name ?: "Host")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    updateUi()
                }
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onInputChanged(buttons: Int, hat: Byte, x: Byte, y: Byte) {
        hidManager.sendReport(buttons, hat, x, y)
    }

    override fun onUserActivity() {
        resetInactivityTimer()
        if (isScreenDimmed) {
            dimScreen(false)
        }
    }

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(dimRunnable)
        inactivityHandler.postDelayed(dimRunnable, 120_000L) // 2 minutes
    }

    private fun dimScreen(dim: Boolean) {
        isScreenDimmed = dim
        val lp = window.attributes
        lp.screenBrightness = if (dim) 0.05f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        resetInactivityTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityHandler.removeCallbacksAndMessages(null)
        hidManager.destroy()
    }
}
