// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.rutger.wsconnect.AppLog
import com.rutger.wsconnect.Diag
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Full-screen trackpad. 1 finger moves, tap = left click, 2-finger drag scrolls, 2-finger tap =
 * right click. Volume up = left button (held while pressed, so it drags), volume down = right click.
 * Volume keys only reach apps in the foreground, so this only works while this screen is open.
 * 3-finger tap = calibrate: the next swipe marks "up" (snapped to 90°, saved), so the phone can be
 * held any way round, e.g. sideways with the volume keys under a finger. Screen locked to portrait
 * (manifest) so auto-rotate can't shift the calibration.
 */
class TrackpadActivity : Activity() {

    private lateinit var deviceId: String
    /** Every send goes through here: counts, and logs when the plugin appears/disappears (sends dropped). */
    private val plugin: MousePadPlugin?
        get() = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin::class.java).also { p ->
            if (p != null) sent++ else dropped++
            if ((p != null) != pluginSeen) {
                pluginSeen = p != null
                AppLog.i("trackpad: " + if (p != null) "mousepad plugin available, sending" else "input dropped: ${pluginState()}")
            }
        }
    private var pluginSeen: Boolean? = null
    private var sent = 0
    private var dropped = 0
    private var leftHeld = false
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Quarter turns clockwise from portrait to the user's "up": 0 = top edge, 1 = right edge, … */
    private var upEdge = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return finish()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        upEdge = prefs.getInt(KEY_UP_EDGE, 0)
        setContentView(Pad(this))
        pluginSeen = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin::class.java) != null
        AppLog.i("trackpad: open (${pluginState()})")
    }

    override fun onDestroy() {
        if (::deviceId.isInitialized) AppLog.i("trackpad: close ($sent sent, $dropped dropped)")
        super.onDestroy()
    }

    private fun pluginState(): String {
        val d = KdeConnect.getInstance().getDevice(deviceId) ?: return "device $deviceId unknown"
        return "${d.name}: reachable=${d.isReachable} mousepad=${Diag.plugin(d, MousePadPlugin::class.java)}"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.repeatCount == 0 && !leftHeld) { leftHeld = true; plugin?.sendSingleHold() }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { releaseLeft(); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { plugin?.sendRightClick(); return true }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onPause() {
        releaseLeft() // never leave the laptop's left button stuck down
        super.onPause()
    }

    private fun releaseLeft() {
        if (leftHeld) { leftHeld = false; plugin?.sendSingleRelease() }
    }

    @SuppressLint("ViewConstructor")
    private inner class Pad(context: Context) : View(context) {
        private val scale = STANDARD_DPI / resources.displayMetrics.xdpi
        private val slop = 12 * resources.displayMetrics.density
        private var lastX = 0f
        private var lastY = 0f
        private var downAt = 0L
        private var maxPointers = 0
        private var travelled = 0f
        private var scrollX = 0.0
        private var scrollY = 0.0
        private var calibrating = false
        private var startX = 0f
        private var startY = 0f
        private val minSwipe = 60 * resources.displayMetrics.density
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 255, 255, 255)
            textAlign = Paint.Align.CENTER
            textSize = 15 * resources.displayMetrics.scaledDensity
        }

        init { setBackgroundColor(Color.rgb(18, 18, 20)) }

        override fun onDraw(canvas: Canvas) {
            val lines = if (calibrating) listOf("Veeg één keer naar jouw boven", "tik = annuleren")
            else listOf("Trackpad", "tik = klik · 2 vingers = scroll / rechtsklik", "volume + = linkerknop · volume − = rechterknop", "3 vingers tik = boven instellen")
            // Text upright in the user's frame.
            canvas.save()
            canvas.rotate(90f * upEdge, width / 2f, height / 2f)
            lines.forEachIndexed { i, s -> canvas.drawText(s, width / 2f, height / 2f + (i - (lines.size - 1) / 2f) * hint.textSize * 1.8f, hint) }
            canvas.restore()
        }

        /** Screen delta → user's frame (rotated by [upEdge] quarter turns). */
        private fun toUser(dx: Float, dy: Float): Pair<Float, Float> = when (upEdge) {
            1 -> dy to -dx
            2 -> -dx to -dy
            3 -> -dy to dx
            else -> dx to dy
        }

        private fun calibrate(sx: Float, sy: Float) {
            calibrating = false
            if (hypot(sx, sy) >= minSwipe) {
                upEdge = if (abs(sx) > abs(sy)) { if (sx > 0) 1 else 3 } else { if (sy < 0) 0 else 2 }
                prefs.edit().putInt(KEY_UP_EDGE, upEdge).apply()
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
            invalidate()
        }

        private fun centroid(e: MotionEvent): Pair<Float, Float> {
            var x = 0f; var y = 0f
            for (i in 0 until e.pointerCount) { x += e.getX(i); y += e.getY(i) }
            return x / e.pointerCount to y / e.pointerCount
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = plugin
            val (x, y) = centroid(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis(); maxPointers = 1; travelled = 0f
                    startX = x; startY = y
                    scrollX = 0.0; scrollY = 0.0
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    maxPointers = maxOf(maxPointers, e.pointerCount)
                    // Centroid jumps when a finger lands/lifts: restart from the new one, don't move.
                    val pointers = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.pointerCount - 1 else e.pointerCount
                    var nx = 0f; var ny = 0f
                    for (i in 0 until e.pointerCount) if (e.actionMasked != MotionEvent.ACTION_POINTER_UP || i != e.actionIndex) { nx += e.getX(i); ny += e.getY(i) }
                    lastX = nx / pointers; lastY = ny / pointers
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (dx, dy) = toUser(x - lastX, y - lastY)
                    travelled += hypot(dx, dy)
                    if (e.pointerCount >= 2) {
                        scrollX += -dx * SCROLL_FACTOR
                        scrollY += -dy * SCROLL_FACTOR
                        if (abs(scrollX) > MIN_SCROLL || abs(scrollY) > MIN_SCROLL) {
                            // Same sign convention as upstream MousePadActivity (non-reversed).
                            p?.sendScroll(-scrollX, scrollY)
                            scrollX = 0.0; scrollY = 0.0
                        }
                    } else if (maxPointers == 1 && !calibrating) {
                        p?.sendMouseDelta(dx * scale, dy * scale)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val tap = travelled < slop && SystemClock.uptimeMillis() - downAt < TAP_MS
                    when {
                        calibrating && maxPointers == 1 -> calibrate(x - startX, y - startY)
                        calibrating -> {}
                        tap && maxPointers >= 3 -> { calibrating = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); invalidate() }
                        tap && maxPointers == 2 -> p?.sendRightClick()
                        tap -> p?.sendLeftClick()
                    }
                }
            }
            lastX = x; lastY = y
            return true
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        private const val STANDARD_DPI = 240f
        private const val SCROLL_FACTOR = 0.35
        private const val MIN_SCROLL = 2.5
        private const val TAP_MS = 220L
        private const val PREFS = "wsconnect_trackpad"
        private const val KEY_UP_EDGE = "up_edge"

        fun intent(c: Context, deviceId: String) =
            Intent(c, TrackpadActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}
