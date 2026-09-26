package com.controlx.gbatvplayer.input

import android.content.Context
import android.content.SharedPreferences
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.controlx.nativemgba.MgbaBridge

class InputManager(private val context: Context) {

    companion object {
        private const val PREFS_INPUT = "gba_input_mapping"
        private const val DEADZONE = 0.40f

        const val ACTION_GBA_A = "ACTION_A"
        const val ACTION_GBA_B = "ACTION_B"
        const val ACTION_GBA_L = "ACTION_L"
        const val ACTION_GBA_R = "ACTION_R"
        const val ACTION_GBA_START = "ACTION_START"
        const val ACTION_GBA_SELECT = "ACTION_SELECT"
        const val ACTION_GBA_UP = "ACTION_UP"
        const val ACTION_GBA_DOWN = "ACTION_DOWN"
        const val ACTION_GBA_LEFT = "ACTION_LEFT"
        const val ACTION_GBA_RIGHT = "ACTION_RIGHT"
    }

    interface PocketPadListener {
        fun onPocketPadDetected(deviceName: String)
    }

    var pocketPadListener: PocketPadListener? = null
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_INPUT, Context.MODE_PRIVATE)

    @Volatile
    var keysMask: Int = 0
        private set

    // Set of detected controllers
    private val detectedDevices = HashSet<Int>()

    // KeyCode to GBA action mapping
    private val keyMapping = HashMap<Int, Int>()

    init {
        loadMappings()
    }

    fun loadMappings() {
        keyMapping.clear()
        // Default mappings
        mapButton(KeyEvent.KEYCODE_BUTTON_A, MgbaBridge.KEY_A, ACTION_GBA_A)
        mapButton(KeyEvent.KEYCODE_BUTTON_B, MgbaBridge.KEY_B, ACTION_GBA_B)
        mapButton(KeyEvent.KEYCODE_BUTTON_X, MgbaBridge.KEY_B, "ACTION_X") // alternative B
        mapButton(KeyEvent.KEYCODE_BUTTON_Y, MgbaBridge.KEY_A, "ACTION_Y") // alternative A
        mapButton(KeyEvent.KEYCODE_BUTTON_L1, MgbaBridge.KEY_L, ACTION_GBA_L)
        mapButton(KeyEvent.KEYCODE_BUTTON_R1, MgbaBridge.KEY_R, ACTION_GBA_R)
        mapButton(KeyEvent.KEYCODE_BUTTON_L2, MgbaBridge.KEY_L, "ACTION_L2")
        mapButton(KeyEvent.KEYCODE_BUTTON_R2, MgbaBridge.KEY_R, "ACTION_R2")
        mapButton(KeyEvent.KEYCODE_BUTTON_START, MgbaBridge.KEY_START, ACTION_GBA_START)
        mapButton(KeyEvent.KEYCODE_BUTTON_SELECT, MgbaBridge.KEY_SELECT, ACTION_GBA_SELECT)

        // D-Pad keys
        mapButton(KeyEvent.KEYCODE_DPAD_UP, MgbaBridge.KEY_UP, ACTION_GBA_UP)
        mapButton(KeyEvent.KEYCODE_DPAD_DOWN, MgbaBridge.KEY_DOWN, ACTION_GBA_DOWN)
        mapButton(KeyEvent.KEYCODE_DPAD_LEFT, MgbaBridge.KEY_LEFT, ACTION_GBA_LEFT)
        mapButton(KeyEvent.KEYCODE_DPAD_RIGHT, MgbaBridge.KEY_RIGHT, ACTION_GBA_RIGHT)

        // TV Remote navigation keys
        mapButton(KeyEvent.KEYCODE_DPAD_CENTER, MgbaBridge.KEY_A, "ACTION_REMOTE_ENTER")
        mapButton(KeyEvent.KEYCODE_ENTER, MgbaBridge.KEY_A, "ACTION_ENTER")
    }

    private fun mapButton(defaultKeyCode: Int, gbaKey: Int, prefKey: String) {
        val mappedCode = prefs.getInt(prefKey, defaultKeyCode)
        keyMapping[mappedCode] = gbaKey
    }

    fun saveMapping(prefKey: String, keyCode: Int) {
        prefs.edit().putInt(prefKey, keyCode).apply()
        loadMappings()
    }

    fun resetMappings() {
        prefs.edit().clear().apply()
        loadMappings()
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        checkDevice(event.device)

        val gbaKey = keyMapping[keyCode]
        if (gbaKey != null) {
            keysMask = keysMask or gbaKey
            return true
        }
        return false
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val gbaKey = keyMapping[keyCode]
        if (gbaKey != null) {
            keysMask = keysMask and gbaKey.inv()
            return true
        }
        return false
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
            (event.source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
        ) {
            return false
        }

        checkDevice(event.device)

        // Read Hat switch (D-pad on many gamepads)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        // Read Analog Left Stick
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)

        val x = if (Math.abs(hatX) > DEADZONE) hatX else stickX
        val y = if (Math.abs(hatY) > DEADZONE) hatY else stickY

        var mask = keysMask
        // Clear directional keys
        mask = mask and (MgbaBridge.KEY_UP or MgbaBridge.KEY_DOWN or MgbaBridge.KEY_LEFT or MgbaBridge.KEY_RIGHT).inv()

        if (x < -DEADZONE) mask = mask or MgbaBridge.KEY_LEFT
        if (x > DEADZONE) mask = mask or MgbaBridge.KEY_RIGHT
        if (y < -DEADZONE) mask = mask or MgbaBridge.KEY_UP
        if (y > DEADZONE) mask = mask or MgbaBridge.KEY_DOWN

        keysMask = mask
        return true
    }

    private fun checkDevice(device: InputDevice?) {
        if (device == null) return
        if (detectedDevices.add(device.id)) {
            val name = device.name ?: ""
            if (name.contains("PocketPad", ignoreCase = true)) {
                pocketPadListener?.onPocketPadDetected(name)
            }
        }
    }
}
