package com.controlx.pocketpad.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.controlx.pocketpad.hid.GamepadHidDescriptor
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

class TouchControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface InputChangeListener {
        fun onInputChanged(buttons: Int, hat: Byte, x: Byte, y: Byte)
        fun onUserActivity()
    }

    var inputChangeListener: InputChangeListener? = null
    var isHapticsEnabled = false

    // Button states
    private var buttonMask = 0
    private var hatState = GamepadHidDescriptor.HAT_CENTER
    private var axisX: Byte = 0
    private var axisY: Byte = 0

    // Paints
    private val paintDpadBase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2332")
        style = Paint.Style.FILL
    }
    private val paintDpadActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#434C68")
        style = Paint.Style.FILL
    }
    private val paintDpadBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E344A")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val paintButtonA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F")
        style = Paint.Style.FILL
    }
    private val paintButtonAPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
    }
    private val paintButtonB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7B1FA2")
        style = Paint.Style.FILL
    }
    private val paintButtonBPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BA68C8")
        style = Paint.Style.FILL
    }
    private val paintShoulder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#282D3F")
        style = Paint.Style.FILL
    }
    private val paintShoulderPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A5473")
        style = Paint.Style.FILL
    }
    private val paintMenuBtn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#252A3C")
        style = Paint.Style.FILL
    }
    private val paintMenuBtnPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A5270")
        style = Paint.Style.FILL
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E6ED")
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Touch bounds
    private var dpadCenterX = 0f
    private var dpadCenterY = 0f
    private var dpadRadius = 0f

    private var buttonACenterX = 0f
    private var buttonACenterY = 0f
    private var buttonARadius = 0f

    private var buttonBCenterX = 0f
    private var buttonBCenterY = 0f
    private var buttonBRadius = 0f

    private val rectL = RectF()
    private val rectR = RectF()
    private val rectSelect = RectF()
    private val rectStart = RectF()

    // Multi-touch tracking
    private val pointerTargetMap = HashMap<Int, Int>() // pointerId -> Target ID
    companion object {
        const val TARGET_NONE = 0
        const val TARGET_DPAD = 1
        const val TARGET_BTN_A = 2
        const val TARGET_BTN_B = 3
        const val TARGET_BTN_L = 4
        const val TARGET_BTN_R = 5
        const val TARGET_SELECT = 6
        const val TARGET_START = 7
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val width = w.toFloat()
        val height = h.toFloat()

        // L & R Shoulders at top corners
        val shoulderWidth = width * 0.28f
        val shoulderHeight = height * 0.16f
        rectL.set(16f, 16f, 16f + shoulderWidth, 16f + shoulderHeight)
        rectR.set(width - 16f - shoulderWidth, 16f, width - 16f, 16f + shoulderHeight)

        // D-Pad on lower left
        dpadRadius = height * 0.28f
        dpadCenterX = width * 0.20f
        dpadCenterY = height * 0.62f

        // Action Buttons on lower right (GBA angled layout: B lower left, A upper right)
        buttonARadius = height * 0.14f
        buttonBRadius = buttonARadius
        buttonACenterX = width * 0.85f
        buttonACenterY = height * 0.54f

        buttonBCenterX = width * 0.72f
        buttonBCenterY = height * 0.68f

        // Select and Start pill buttons in bottom center
        val menuWidth = width * 0.10f
        val menuHeight = height * 0.08f
        val menuCenterY = height * 0.85f
        val centerX = width * 0.5f

        rectSelect.set(centerX - menuWidth - 20f, menuCenterY - menuHeight / 2, centerX - 20f, menuCenterY + menuHeight / 2)
        rectStart.set(centerX + 20f, menuCenterY - menuHeight / 2, centerX + menuWidth + 20f, menuCenterY + menuHeight / 2)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw Shoulders L & R
        val lPressed = (buttonMask and GamepadHidDescriptor.BUTTON_L1) != 0
        canvas.drawRoundRect(rectL, 20f, 20f, if (lPressed) paintShoulderPressed else paintShoulder)
        canvas.drawText("L", rectL.centerX(), rectL.centerY() + 10f, paintText)

        val rPressed = (buttonMask and GamepadHidDescriptor.BUTTON_R1) != 0
        canvas.drawRoundRect(rectR, 20f, 20f, if (rPressed) paintShoulderPressed else paintShoulder)
        canvas.drawText("R", rectR.centerX(), rectR.centerY() + 10f, paintText)

        // Draw D-Pad
        drawDpad(canvas)

        // Draw Action Buttons (A & B)
        val bPressed = (buttonMask and GamepadHidDescriptor.BUTTON_B) != 0
        canvas.drawCircle(buttonBCenterX, buttonBCenterY, buttonBRadius, if (bPressed) paintButtonBPressed else paintButtonB)
        canvas.drawText("B", buttonBCenterX, buttonBCenterY + 12f, paintText)

        val aPressed = (buttonMask and GamepadHidDescriptor.BUTTON_A) != 0
        canvas.drawCircle(buttonACenterX, buttonACenterY, buttonARadius, if (aPressed) paintButtonAPressed else paintButtonA)
        canvas.drawText("A", buttonACenterX, buttonACenterY + 12f, paintText)

        // Draw Select & Start buttons
        val selectPressed = (buttonMask and GamepadHidDescriptor.BUTTON_SELECT) != 0
        canvas.drawRoundRect(rectSelect, 15f, 15f, if (selectPressed) paintMenuBtnPressed else paintMenuBtn)
        canvas.drawText("SELECT", rectSelect.centerX(), rectSelect.centerY() + 10f, paintText)

        val startPressed = (buttonMask and GamepadHidDescriptor.BUTTON_START) != 0
        canvas.drawRoundRect(rectStart, 15f, 15f, if (startPressed) paintMenuBtnPressed else paintMenuBtn)
        canvas.drawText("START", rectStart.centerX(), rectStart.centerY() + 10f, paintText)
    }

    private fun drawDpad(canvas: Canvas) {
        val crossArm = dpadRadius * 0.40f
        val crossLen = dpadRadius

        // D-Pad cross outline/background
        val path = Path().apply {
            moveTo(dpadCenterX - crossArm, dpadCenterY - crossLen)
            lineTo(dpadCenterX + crossArm, dpadCenterY - crossLen)
            lineTo(dpadCenterX + crossArm, dpadCenterY - crossArm)
            lineTo(dpadCenterX + crossLen, dpadCenterY - crossArm)
            lineTo(dpadCenterX + crossLen, dpadCenterY + crossArm)
            lineTo(dpadCenterX + crossArm, dpadCenterY + crossArm)
            lineTo(dpadCenterX + crossArm, dpadCenterY + crossLen)
            lineTo(dpadCenterX - crossArm, dpadCenterY + crossLen)
            lineTo(dpadCenterX - crossArm, dpadCenterY + crossArm)
            lineTo(dpadCenterX - crossLen, dpadCenterY + crossArm)
            lineTo(dpadCenterX - crossLen, dpadCenterY - crossArm)
            lineTo(dpadCenterX - crossArm, dpadCenterY - crossArm)
            close()
        }

        canvas.drawPath(path, paintDpadBase)
        canvas.drawPath(path, paintDpadBorder)

        // Center hub
        canvas.drawCircle(dpadCenterX, dpadCenterY, crossArm * 0.8f, paintDpadActive)

        // Direction indicators
        canvas.drawText("▲", dpadCenterX, dpadCenterY - crossLen * 0.55f, paintText)
        canvas.drawText("▼", dpadCenterX, dpadCenterY + crossLen * 0.75f, paintText)
        canvas.drawText("◀", dpadCenterX - crossLen * 0.65f, dpadCenterY + 10f, paintText)
        canvas.drawText("▶", dpadCenterX + crossLen * 0.65f, dpadCenterY + 10f, paintText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        inputChangeListener?.onUserActivity()

        var newButtons = 0
        var newHat = GamepadHidDescriptor.HAT_CENTER
        var newX: Byte = 0
        var newY: Byte = 0

        val action = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                pointerTargetMap[pointerId] = getTargetAt(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                pointerTargetMap.remove(pointerId)
            }
        }

        // Process all active pointers
        for (i in 0 until event.pointerCount) {
            val pid = event.getPointerId(i)
            val px = event.getX(i)
            val py = event.getY(i)

            // Dynamic tracking: allows sliding from center of D-pad or between buttons
            val target = getTargetAt(px, py)
            pointerTargetMap[pid] = target

            when (target) {
                TARGET_DPAD -> {
                    val dx = px - dpadCenterX
                    val dy = py - dpadCenterY
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (dist > dpadRadius * 0.15f) {
                        val angle = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0
                        // 8-way directional sector calculation
                        newHat = when {
                            angle >= 337.5 || angle < 22.5 -> GamepadHidDescriptor.HAT_RIGHT
                            angle in 22.5..67.5 -> GamepadHidDescriptor.HAT_DOWN_RIGHT
                            angle in 67.5..112.5 -> GamepadHidDescriptor.HAT_DOWN
                            angle in 112.5..157.5 -> GamepadHidDescriptor.HAT_DOWN_LEFT
                            angle in 157.5..202.5 -> GamepadHidDescriptor.HAT_LEFT
                            angle in 202.5..247.5 -> GamepadHidDescriptor.HAT_UP_LEFT
                            angle in 247.5..292.5 -> GamepadHidDescriptor.HAT_UP
                            angle in 292.5..337.5 -> GamepadHidDescriptor.HAT_UP_RIGHT
                            else -> GamepadHidDescriptor.HAT_CENTER
                        }

                        // Analog fallback axis
                        newX = (dx / dpadRadius * 127f).coerceIn(-127f, 127f).toInt().toByte()
                        newY = (dy / dpadRadius * 127f).coerceIn(-127f, 127f).toInt().toByte()
                    }
                }
                TARGET_BTN_A -> newButtons = newButtons or GamepadHidDescriptor.BUTTON_A
                TARGET_BTN_B -> newButtons = newButtons or GamepadHidDescriptor.BUTTON_B
                TARGET_BTN_L -> newButtons = newButtons or GamepadHidDescriptor.BUTTON_L1
                TARGET_BTN_R -> newButtons = newButtons or GamepadHidDescriptor.BUTTON_R1
                TARGET_SELECT -> newButtons = newButtons or GamepadHidDescriptor.BUTTON_SELECT
                TARGET_START -> newButtons = newButtons or GamepadHidDescriptor.BUTTON_START
            }
        }

        // Haptic feedback trigger on newly pressed buttons
        if (isHapticsEnabled) {
            val newlyPressed = (newButtons and buttonMask.inv()) or (if (newHat != GamepadHidDescriptor.HAT_CENTER && hatState == GamepadHidDescriptor.HAT_CENTER) 1 else 0)
            if (newlyPressed != 0) {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }

        // Check if state changed
        if (newButtons != buttonMask || newHat != hatState || newX != axisX || newY != axisY) {
            buttonMask = newButtons
            hatState = newHat
            axisX = newX
            axisY = newY
            inputChangeListener?.onInputChanged(buttonMask, hatState, axisX, axisY)
            invalidate()
        }

        return true
    }

    private fun getTargetAt(x: Float, y: Float): Int {
        // Shoulder L & R
        if (rectL.contains(x, y)) return TARGET_BTN_L
        if (rectR.contains(x, y)) return TARGET_BTN_R

        // Action Buttons
        if (hypot((x - buttonACenterX).toDouble(), (y - buttonACenterY).toDouble()) <= buttonARadius * 1.35f) {
            return TARGET_BTN_A
        }
        if (hypot((x - buttonBCenterX).toDouble(), (y - buttonBCenterY).toDouble()) <= buttonBRadius * 1.35f) {
            return TARGET_BTN_B
        }

        // Select & Start
        if (rectSelect.contains(x, y)) return TARGET_SELECT
        if (rectStart.contains(x, y)) return TARGET_START

        // D-Pad
        if (hypot((x - dpadCenterX).toDouble(), (y - dpadCenterY).toDouble()) <= dpadRadius * 1.25f) {
            return TARGET_DPAD
        }

        return TARGET_NONE
    }
}
