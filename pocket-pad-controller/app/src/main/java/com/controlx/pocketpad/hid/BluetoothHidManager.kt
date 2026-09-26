package com.controlx.pocketpad.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothHidManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHidManager"
        private const val PREFS_NAME = "pocket_pad_prefs"
        private const val KEY_LAST_DEVICE = "last_device_mac"
    }

    interface Listener {
        fun onRegistrationStateChanged(registered: Boolean)
        fun onConnectionStateChanged(device: BluetoothDevice?, state: Int)
        fun onError(message: String)
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var listener: Listener? = null
    var hidDevice: BluetoothHidDevice? = null
        private set

    var connectedDevice: BluetoothDevice? = null
        private set

    val isRegistered = AtomicBoolean(false)
    val isConnected: Boolean
        get() = connectedDevice != null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(TAG, "HID Device Proxy connected")
                hidDevice = proxy as BluetoothHidDevice
                registerApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(TAG, "HID Device Proxy disconnected")
                hidDevice = null
                isRegistered.set(false)
                connectedDevice = null
                listener?.onRegistrationStateChanged(false)
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged: registered=$registered, device=${pluggedDevice?.name}")
            isRegistered.set(registered)
            listener?.onRegistrationStateChanged(registered)
            if (registered && pluggedDevice != null) {
                connectedDevice = pluggedDevice
                listener?.onConnectionStateChanged(pluggedDevice, BluetoothProfile.STATE_CONNECTED)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.d(TAG, "onConnectionStateChanged: device=${device.name}, state=$state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    saveLastDevice(device.address)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedDevice?.address == device.address) {
                        connectedDevice = null
                    }
                }
            }
            listener?.onConnectionStateChanged(device, state)
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            // Respond with empty/idle report if requested
            if (id == GamepadHidDescriptor.REPORT_ID_GAMEPAD.toByte()) {
                val report = GamepadHidDescriptor.createReport(0, GamepadHidDescriptor.HAT_CENTER, 0, 0)
                try {
                    hidDevice?.replyReport(device, type, id, report)
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException on replyReport", e)
                }
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            // No output reports expected for basic gamepad
        }
    }

    fun init() {
        if (bluetoothAdapter == null) {
            listener?.onError("Bluetooth not available on this device")
            return
        }
        bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    private fun registerApp() {
        val hid = hidDevice ?: return
        if (isRegistered.get()) return

        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "PocketPad-Controller",
            "Pocket Pad GBA Gamepad",
            "ControlX",
            0x08.toByte(), // HID Gamepad subclass
            GamepadHidDescriptor.REPORT_DESCRIPTOR
        )

        val inQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
        )
        val outQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
        )

        try {
            val registered = hid.registerApp(
                sdpSettings,
                inQos,
                outQos,
                ContextCompat.getMainExecutor(context),
                hidCallback
            )
            Log.d(TAG, "registerApp call result: $registered")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permissions to register HID app", e)
            listener?.onError("Missing Bluetooth permission to register HID service")
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val hid = hidDevice ?: run {
            listener?.onError("HID Service not ready yet")
            return
        }
        try {
            listener?.onConnectionStateChanged(device, BluetoothProfile.STATE_CONNECTING)
            hid.connect(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on connect", e)
            listener?.onError("Permission denied to connect to Bluetooth device")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val hid = hidDevice ?: return
        val device = connectedDevice ?: return
        try {
            hid.disconnect(device)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on disconnect", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendReport(buttons: Int, hat: Byte, x: Byte, y: Byte): Boolean {
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false
        val report = GamepadHidDescriptor.createReport(buttons, hat, x, y)
        return try {
            hid.sendReport(device, GamepadHidDescriptor.REPORT_ID_GAMEPAD, report)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on sendReport", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun getLastDeviceAddress(): String? {
        return prefs.getString(KEY_LAST_DEVICE, null)
    }

    private fun saveLastDevice(address: String) {
        prefs.edit().putString(KEY_LAST_DEVICE, address).apply()
    }

    @SuppressLint("MissingPermission")
    fun reconnectLastDevice(): Boolean {
        val address = getLastDeviceAddress() ?: return false
        val adapter = bluetoothAdapter ?: return false
        return try {
            val device = adapter.getRemoteDevice(address)
            connect(device)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconnect to $address", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun destroy() {
        try {
            if (isRegistered.get()) {
                hidDevice?.unregisterApp()
            }
            if (hidDevice != null) {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up HID manager", e)
        }
    }
}
