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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Full-screen trackpad. 1 finger moves, tap = left click, 2-finger drag scrolls, 2-finger tap =
 * right click. Volume up = left button (held while pressed, so it drags), volume down = right click.
 * Volume keys only reach apps in the foreground, so this only works while this screen is open.
 */
class TrackpadActivity : Activity() {

    private lateinit var deviceId: String
    private val plugin: MousePadPlugin?
        get() = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin::class.java)
    private var leftHeld = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return finish()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(Pad(this))
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
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 255, 255, 255)
            textAlign = Paint.Align.CENTER
            textSize = 15 * resources.displayMetrics.scaledDensity
        }

        init { setBackgroundColor(Color.rgb(18, 18, 20)) }

        override fun onDraw(canvas: Canvas) {
            val lines = listOf("Trackpad", "tik = klik · 2 vingers = scroll / rechtsklik", "volume + = linkerknop · volume − = rechterknop")
            lines.forEachIndexed { i, s -> canvas.drawText(s, width / 2f, height / 2f + (i - 1) * hint.textSize * 1.8f, hint) }
        }

        private fun centroid(e: MotionEvent): Pair<Float, Float> {
            var x = 0f; var y = 0f
            for (i in 0 until e.pointerCount) { x += e.getX(i); y += e.getY(i) }
            return x / e.pointerCount to y / e.pointerCount
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = plugin ?: return true
            val (x, y) = centroid(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis(); maxPointers = 1; travelled = 0f
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
                    val dx = x - lastX
                    val dy = y - lastY
                    travelled += hypot(dx, dy)
                    if (e.pointerCount >= 2) {
                        scrollX += -dx * SCROLL_FACTOR
                        scrollY += -dy * SCROLL_FACTOR
                        if (abs(scrollX) > MIN_SCROLL || abs(scrollY) > MIN_SCROLL) {
                            // Same sign convention as upstream MousePadActivity (non-reversed).
                            p.sendScroll(-scrollX, scrollY)
                            scrollX = 0.0; scrollY = 0.0
                        }
                    } else if (maxPointers == 1) {
                        p.sendMouseDelta(dx * scale, dy * scale)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val tap = travelled < slop && SystemClock.uptimeMillis() - downAt < TAP_MS
                    if (tap) if (maxPointers >= 2) p.sendRightClick() else p.sendLeftClick()
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

        fun intent(c: Context, deviceId: String) =
            Intent(c, TrackpadActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}
