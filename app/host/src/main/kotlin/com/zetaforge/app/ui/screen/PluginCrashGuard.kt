package com.zetaforge.app.ui.screen

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * The container a plugin's screen is drawn inside, and the reason a plugin that
 * throws does not take the Host down with it.
 *
 * `ZetaPluginRuntime.execute` can promise containment cheaply: it calls one
 * suspend function on a background dispatcher and wraps it in a `try`. A screen
 * has no such single call. Its code runs on the main thread, re-entered by the
 * framework from four different directions — touch dispatch, key dispatch,
 * measure/layout, draw — and an exception raised on any of them unwinds through
 * `ViewRootImpl`, which kills the process.
 *
 * All four enter the plugin's view subtree through this one view, so all four
 * can be caught here. What cannot be caught here is composition itself, which
 * runs on the Recomposer's coroutine: that half is handled in
 * [PluginScreenActivity] by giving the plugin's composition its own recomposer
 * with its own exception handler.
 *
 * ### After a catch
 * The view tree is left mid-operation, so nothing is repaired in place: the
 * report is *posted*, and the Activity replaces this whole container with an
 * error screen and unloads the plugin. Swallowing the exception and carrying on
 * would leave a half-laid-out subtree that fails again on the next frame.
 */
internal class PluginCrashGuard(
    context: Context,
    private val onCrash: (Throwable) -> Unit,
) : FrameLayout(context) {

    /** Set once a crash is reported, so one failure cannot report twice. */
    private var failed = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = guard(true) {
        super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = guard(true) {
        super.dispatchKeyEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        guard(Unit) { super.onMeasure(widthMeasureSpec, heightMeasureSpec) }
        // A failed measure still has to leave a measured dimension behind, or
        // the parent's layout pass throws in turn - a second, misleading crash
        // on top of the real one.
        if (failed) {
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        guard(Unit) { super.onLayout(changed, left, top, right, bottom) }
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        guard(Unit) { super.dispatchDraw(canvas) }
    }

    private inline fun <T> guard(fallback: T, block: () -> T): T =
        if (failed) {
            fallback
        } else {
            try {
                block()
            } catch (t: Throwable) {
                failed = true
                // Posted, never inline: we are inside a framework callback and
                // the Activity is about to tear this whole view down.
                post { onCrash(t) }
                fallback
            }
        }
}
