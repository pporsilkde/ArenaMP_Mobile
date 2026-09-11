package ui.theme

import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.libopenmw.openmw.R

/** Compiled-in material. Does not change updater cancellation or install logic. */
object ArenaGlass {
    class Builder(context: Context) : AlertDialog.Builder(context, R.style.ArenaGlassDialog) {
        override fun create(): AlertDialog = super.create().also { dialog ->
            dialog.setOnShowListener { styleDialog(dialog) }
        }
        override fun show(): AlertDialog = create().also { it.show() }
    }

    @Suppress("DEPRECATION")
    class Progress(context: Context) : ProgressDialog(context, R.style.ArenaGlassDialog) {
        override fun onStart() {
            super.onStart()
            styleDialog(this)
        }
    }

    fun styleDialog(dialog: Dialog) {
        val window = dialog.window ?: return
        val density = dialog.context.resources.displayMetrics.density
        val surface = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(50, 50, 52), Color.rgb(32, 33, 36))).apply {
            cornerRadius = 24f * density
            setStroke((density + 0.5f).toInt().coerceAtLeast(1), Color.rgb(113, 102, 86))
        }
        window.setBackgroundDrawable(surface)
        window.decorView.elevation = 16f * density
        if (Build.VERSION.SDK_INT >= 31) Blur31.attach(window, surface, density)
    }

    // Only public API 31 methods are reflected, preserving the existing SDK 29
    // build and minSdk 21. Keep Consumer out of classes loaded on Android 5/6.
    private object Blur31 {
        fun attach(window: Window, surface: GradientDrawable, density: Float) {
            val manager = window.windowManager
            try {
                val api = WindowManager::class.java
                val enabled = api.getMethod("isCrossWindowBlurEnabled")
                val radius = Window::class.java.getMethod("setBackgroundBlurRadius", Int::class.javaPrimitiveType)
                fun refresh(available: Boolean) {
                    try {
                        radius.invoke(window, if (available) (28 * density).toInt() else 0)
                        surface.alpha = if (available) 220 else 255
                        window.setDimAmount(if (available) 0.30f else 0.55f)
                    } catch (_: Exception) {
                        surface.alpha = 255
                        window.setDimAmount(0.55f)
                    }
                }
                refresh(enabled.invoke(manager) as? Boolean ?: false)
                val listener = java.util.function.Consumer<Boolean> { available ->
                    window.decorView.post { refresh(available) }
                }
                val consumer = java.util.function.Consumer::class.java
                api.getMethod("addCrossWindowBlurEnabledListener", consumer).invoke(manager, listener)
                window.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {}
                    override fun onViewDetachedFromWindow(view: View) {
                        try { api.getMethod("removeCrossWindowBlurEnabledListener", consumer).invoke(manager, listener) }
                        catch (_: Exception) { }
                        view.removeOnAttachStateChangeListener(this)
                    }
                })
            } catch (_: Exception) {
                // Unsupported vendor implementation or blur disabled: opaque material.
                surface.alpha = 255
                window.setDimAmount(0.55f)
            }
        }
    }
}
