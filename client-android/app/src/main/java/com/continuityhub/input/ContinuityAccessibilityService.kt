package com.continuityhub.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.continuityhub.proto.PhoneNavAction
import com.continuityhub.proto.PhoneTextAction
import com.continuityhub.proto.PhoneTouchEvent

class ContinuityAccessibilityService : AccessibilityService() {

    private val tag = "ContinuityA11yService"

    companion object {
        var instance: ContinuityAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun executeTouch(event: PhoneTouchEvent): Boolean {
            return instance?.handleTouchEvent(event) ?: false
        }

        fun executeNav(action: PhoneNavAction.Action): Boolean {
            return instance?.handleNavAction(action) ?: false
        }

        fun executeText(textAction: PhoneTextAction): Boolean {
            return instance?.handleTextAction(textAction) ?: false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(tag, "Continuity Accessibility Service connected and active")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(tag, "Continuity Accessibility Service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(tag, "Continuity Accessibility Service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive listener; actions are dispatched from desktop via ContinuityService
    }

    override fun onInterrupt() {
        Log.w(tag, "Continuity Accessibility Service interrupted")
    }

    fun handleTouchEvent(event: PhoneTouchEvent): Boolean {
        val dm: DisplayMetrics = resources.displayMetrics
        val screenWidth = dm.widthPixels.toFloat()
        val screenHeight = dm.heightPixels.toFloat()

        val startX = (event.normalizedX * screenWidth).coerceIn(0f, screenWidth - 1)
        val startY = (event.normalizedY * screenHeight).coerceIn(0f, screenHeight - 1)

        return when (event.type) {
            PhoneTouchEvent.TouchType.TOUCH_TAP -> {
                dispatchTap(startX, startY)
            }
            PhoneTouchEvent.TouchType.TOUCH_SWIPE -> {
                val endX = (event.endX * screenWidth).coerceIn(0f, screenWidth - 1)
                val endY = (event.endY * screenHeight).coerceIn(0f, screenHeight - 1)
                val duration = if (event.durationMs > 0) event.durationMs.toLong() else 200L
                dispatchSwipe(startX, startY, endX, endY, duration)
            }
            PhoneTouchEvent.TouchType.TOUCH_SCROLL -> {
                val scrollDx = event.scrollDx * screenWidth * 0.4f
                val scrollDy = event.scrollDy * screenHeight * 0.4f
                val endX = (startX - scrollDx).coerceIn(0f, screenWidth - 1)
                val endY = (startY - scrollDy).coerceIn(0f, screenHeight - 1)
                dispatchSwipe(startX, startY, endX, endY, 150L)
            }
            PhoneTouchEvent.TouchType.TOUCH_DOWN -> {
                // Short touch tap fallback for down events
                dispatchTap(startX, startY)
            }
            PhoneTouchEvent.TouchType.TOUCH_MOVE -> {
                val endX = (event.endX * screenWidth).coerceIn(0f, screenWidth - 1)
                val endY = (event.endY * screenHeight).coerceIn(0f, screenHeight - 1)
                dispatchSwipe(startX, startY, endX, endY, 100L)
            }
            PhoneTouchEvent.TouchType.TOUCH_UP -> true
            else -> false
        }
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(tag, "Tap gesture completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(tag, "Tap gesture cancelled at ($x, $y)")
            }
        }, null)

        Log.d(tag, "Dispatched tap at ($x, $y), success: $dispatched")
        return dispatched
    }

    private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(80L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, null, null)

        Log.d(tag, "Dispatched swipe from ($x1, $y1) to ($x2, $y2) [${durationMs}ms], success: $dispatched")
        return dispatched
    }

    fun handleNavAction(action: PhoneNavAction.Action): Boolean {
        return when (action) {
            PhoneNavAction.Action.ACTION_BACK -> {
                Log.i(tag, "Executing global navigation BACK")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            PhoneNavAction.Action.ACTION_HOME -> {
                Log.i(tag, "Executing global navigation HOME")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            PhoneNavAction.Action.ACTION_RECENTS -> {
                Log.i(tag, "Executing global navigation RECENTS")
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
            PhoneNavAction.Action.ACTION_NOTIFICATIONS -> {
                Log.i(tag, "Executing global navigation NOTIFICATIONS")
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            }
            PhoneNavAction.Action.ACTION_LOCK -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Log.i(tag, "Executing global navigation LOCK SCREEN")
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                    Log.w(tag, "Lock screen global action requires Android 9.0+ (P)")
                    false
                }
            }
            else -> false
        }
    }

    fun handleTextAction(textAction: PhoneTextAction): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val current = focused.text?.toString() ?: ""
            val newText = current + textAction.text
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
            val set = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(tag, "Injected text into focused input field: '${textAction.text}' (success: $set)")
            return set
        }

        Log.d(tag, "No active editable input field found for text injection")
        return false
    }
}
