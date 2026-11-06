package com.ahmadnayfeh.reel

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Wraps the player and turns swipes into brightness (left half), volume (right half) and seeking (horizontal).
 * Double-tap sides to skip 10 s, double-tap the middle to pause, pinch to zoom. Taps still reach the player.
 */
class GestureLayout(ctx: Context, attrs: AttributeSet?) : FrameLayout(ctx, attrs) {

    interface Listener {
        fun onGestureStart(mode: Int)
        fun onGestureMove(mode: Int, fraction: Float)
        fun onGestureEnd(mode: Int)
        fun onDoubleTapAt(x: Float, width: Int)
        fun onPinch(scale: Float)
        fun onLockedTap()
    }

    companion object {
        const val NONE = 0; const val SEEK = 1; const val BRIGHT = 2; const val VOLUME = 3; const val PINCH = 4
    }

    var listener: Listener? = null
    var locked = false
    var gesturesEnabled = true

    private var mode = NONE
    private var downX = 0f
    private var downY = 0f
    private var ignore = false
    private var pinch = 1f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 2f

    private val tapDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!locked && mode == NONE) listener?.onDoubleTapAt(e.x, width)
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (locked) return false
            if (mode != NONE && mode != PINCH) return false
            pinch = 1f
            start(PINCH)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinch *= detector.scaleFactor
            listener?.onPinch(pinch)
            return true
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!gesturesEnabled) return super.dispatchTouchEvent(ev)
        tapDetector.onTouchEvent(ev)
        scaleDetector.onTouchEvent(ev)
        if (locked) {
            if (ev.actionMasked == MotionEvent.ACTION_UP) listener?.onLockedTap()
            // Controls are disabled while locked; only the unlock button can still receive the touch.
            return super.dispatchTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; mode = NONE
                val edge = height * 0.07f // leave the system gesture areas alone
                ignore = ev.y < edge || ev.y > height - edge
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == NONE && !ignore && ev.pointerCount == 1) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > slop || abs(dy) > slop) {
                        val m = when {
                            abs(dx) > abs(dy) -> SEEK
                            downX < width / 2f -> BRIGHT
                            else -> VOLUME
                        }
                        downX = ev.x; downY = ev.y
                        start(m)
                    }
                } else if (mode == SEEK) {
                    listener?.onGestureMove(SEEK, (ev.x - downX) / width)
                } else if (mode == BRIGHT || mode == VOLUME) {
                    listener?.onGestureMove(mode, -(ev.y - downY) / height)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode != NONE) {
                    val m = mode
                    mode = NONE
                    listener?.onGestureEnd(m)
                    return true
                }
            }
        }
        return if (mode != NONE) true else super.dispatchTouchEvent(ev)
    }

    private fun start(m: Int) {
        if (mode != NONE) return
        mode = m
        listener?.onGestureStart(m)
        // Tell children (the player view) the touch is ours now, so it won't toggle controls.
        val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }
}
