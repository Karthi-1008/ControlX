package com.controlx.pocketpad.hid

object GamepadHidDescriptor {
    const val REPORT_ID_GAMEPAD = 1

    // Bit positions in button mask (16-bit)
    const val BUTTON_A = 1 shl 0
    const val BUTTON_B = 1 shl 1
    const val BUTTON_X = 1 shl 2
    const val BUTTON_Y = 1 shl 3
    const val BUTTON_L1 = 1 shl 4
    const val BUTTON_R1 = 1 shl 5
    const val BUTTON_L2 = 1 shl 6
    const val BUTTON_R2 = 1 shl 7

    const val BUTTON_SELECT = 1 shl 8
    const val BUTTON_START = 1 shl 9

    // Hat switch (D-pad) values
    const val HAT_CENTER = 0.toByte()
    const val HAT_UP = 1.toByte()
    const val HAT_UP_RIGHT = 2.toByte()
    const val HAT_RIGHT = 3.toByte()
    const val HAT_DOWN_RIGHT = 4.toByte()
    const val HAT_DOWN = 5.toByte()
    const val HAT_DOWN_LEFT = 6.toByte()
    const val HAT_LEFT = 7.toByte()
    const val HAT_UP_LEFT = 8.toByte()

    val REPORT_DESCRIPTOR = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),        // Usage Page (Generic Desktop)
        0x09.toByte(), 0x05.toByte(),        // Usage (Gamepad)
        0xA1.toByte(), 0x01.toByte(),        // Collection (Application)
        0xA1.toByte(), 0x00.toByte(),        //   Collection (Physical)
        0x85.toByte(), REPORT_ID_GAMEPAD.toByte(), // Report ID (1)

        // 16 Buttons (A, B, X, Y, L1, R1, L2, R2, Select, Start, etc.)
        0x05.toByte(), 0x09.toByte(),        //     Usage Page (Button)
        0x19.toByte(), 0x01.toByte(),        //     Usage Minimum (Button 1)
        0x29.toByte(), 0x10.toByte(),        //     Usage Maximum (Button 16)
        0x15.toByte(), 0x00.toByte(),        //     Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),        //     Logical Maximum (1)
        0x95.toByte(), 0x10.toByte(),        //     Report Count (16)
        0x75.toByte(), 0x01.toByte(),        //     Report Size (1)
        0x81.toByte(), 0x02.toByte(),        //     Input (Data, Variable, Absolute)

        // Hat Switch (D-Pad): 1-8, 0=centered/null
        0x05.toByte(), 0x01.toByte(),        //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x39.toByte(),        //     Usage (Hat switch)
        0x15.toByte(), 0x01.toByte(),        //     Logical Minimum (1)
        0x25.toByte(), 0x08.toByte(),        //     Logical Maximum (8)
        0x35.toByte(), 0x00.toByte(),        //     Physical Minimum (0)
        0x46.toByte(), 0x3B.toByte(), 0x01.toByte(), // Physical Maximum (315)
        0x65.toByte(), 0x14.toByte(),        //     Unit (Eng Rot: Degree)
        0x75.toByte(), 0x04.toByte(),        //     Report Size (4 bits)
        0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
        0x81.toByte(), 0x42.toByte(),        //     Input (Data, Variable, Absolute, Null State)
        0x75.toByte(), 0x04.toByte(),        //     Report Size (4 bits)
        0x95.toByte(), 0x01.toByte(),        //     Report Count (1)
        0x81.toByte(), 0x01.toByte(),        //     Input (Constant - 4-bit padding)

        // Left Analog Stick / D-Pad fallback axes: X and Y (-127 to 127)
        0x05.toByte(), 0x01.toByte(),        //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(),        //     Usage (X)
        0x09.toByte(), 0x31.toByte(),        //     Usage (Y)
        0x15.toByte(), 0x81.toByte(),        //     Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(),        //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(),        //     Report Size (8)
        0x95.toByte(), 0x02.toByte(),        //     Report Count (2)
        0x81.toByte(), 0x02.toByte(),        //     Input (Data, Variable, Absolute)

        0xC0.toByte(),                      //   End Collection
        0xC0.toByte()                       // End Collection
    )

    fun createReport(buttons: Int, hat: Byte, x: Byte, y: Byte): ByteArray {
        return byteArrayOf(
            (buttons and 0xFF).toByte(),
            ((buttons shr 8) and 0xFF).toByte(),
            hat,
            x,
            y
        )
    }
}
