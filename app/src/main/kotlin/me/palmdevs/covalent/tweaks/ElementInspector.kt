package me.palmdevs.covalent.tweaks

import android.app.Activity
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import de.robv.android.xposed.XposedBridge
import me.palmdevs.covalent.api.tweak
import me.palmdevs.covalent.hook
import me.palmdevs.covalent.loadClassOrNull
import me.palmdevs.covalent.method
import me.palmdevs.covalent.methodHook
import java.lang.ref.WeakReference

/**
 * A tweak that lets React DevTools select elements by tapping them, and highlight elements, in production builds.
 * React Native's own overlay for this is only rendered in development builds.
 * 
 * Touches are intercepted here while inspecting, and passed to `react-devtools-inspector.bundle`.
 */
val elementInspector by tweak {
    apply {
        val reactInstance = classLoader.loadClassOrNull("com.facebook.react.runtime.ReactInstance")
            ?: return@apply log.w("ReactInstance not found, skipping")

        XposedBridge.hookAllConstructors(reactInstance, methodHook {
            after {
                try {
                    ElementInspector.install(runtimeExecutorPointer(thisObject!!))
                } catch (e: Throwable) {
                    log.e("Failed to install element inspector:", e)
                }
            }
        }.build())

        Activity::class.java.method("onResume").hook {
            after { ElementInspector.activity = WeakReference(thisObject as Activity) }
        }

        // The window's root view gets touches before the Activity does, which may handle them itself
        val decorView = classLoader.loadClassOrNull("com.android.internal.policy.DecorView")
            ?: return@apply log.w("DecorView not found, tapping to select elements is unavailable")

        decorView.method("dispatchTouchEvent", MotionEvent::class.java).hook {
            before {
                if (ElementInspector.onTouch(thisObject as View, args[0] as MotionEvent)) result = true
            }
        }
    }
}

object ElementInspector {
    init {
        System.loadLibrary("covalent")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val highlight = HighlightDrawable()

    @Volatile
    private var inspecting = false

    @Volatile
    internal var activity = WeakReference<Activity>(null)

    fun install(executorPointer: Long) = nativeInstall(executorPointer)

    /**
     * Consumes touches while inspecting, so the app doesn't react to them.
     */
    fun onTouch(decorView: View, event: MotionEvent): Boolean {
        if (!inspecting) return false

        // Touch coordinates are relative to the window, like measureInWindow
        val density = decorView.resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                nativeInspectAt(event.x / density, event.y / density, false)

            MotionEvent.ACTION_UP -> nativeInspectAt(event.x / density, event.y / density, true)
        }

        return true
    }

    /**
     * Called from the JS thread when React DevTools starts or stops inspecting.
     */
    @JvmStatic
    private fun setInspecting(inspecting: Boolean) {
        this.inspecting = inspecting
    }

    /**
     * Called from the JS thread with window rectangles (x, y, width, height, ...) in dp, or null to hide.
     */
    @JvmStatic
    private fun highlight(rects: FloatArray?) {
        mainHandler.post {
            val activity = activity.get() ?: return@post
            highlight.show(activity, rects)
        }
    }

    @JvmStatic
    private external fun nativeInstall(executorPointer: Long)

    @JvmStatic
    private external fun nativeInspectAt(x: Float, y: Float, done: Boolean)
}

/**
 * Draws highlights over the whole window, similar to React Native's ElementBox.
 */
private class HighlightDrawable : Drawable() {
    private var rects: FloatArray? = null
    private var density = 1f
    private var attachedTo = WeakReference<Activity>(null)

    private val fill = Paint().apply {
        style = Paint.Style.FILL
        color = 0x553D8BFF
    }

    private val stroke = Paint().apply {
        style = Paint.Style.STROKE
        color = 0xFF3D8BFF.toInt()
    }

    fun show(activity: Activity, rects: FloatArray?) {
        val decorView = activity.window.decorView

        if (attachedTo.get() !== activity) {
            attachedTo.get()?.window?.decorView?.overlay?.remove(this)
            decorView.overlay.add(this)
            attachedTo = WeakReference(activity)
        }

        density = activity.resources.displayMetrics.density
        stroke.strokeWidth = 2 * density
        setBounds(0, 0, decorView.width, decorView.height)

        this.rects = rects
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val rects = rects ?: return

        for (i in 0 until rects.size / 4) {
            val left = rects[i * 4] * density
            val top = rects[i * 4 + 1] * density
            val right = left + rects[i * 4 + 2] * density
            val bottom = top + rects[i * 4 + 3] * density

            canvas.drawRect(left, top, right, bottom, fill)
            canvas.drawRect(left, top, right, bottom, stroke)
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
