package voice

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.libopenmw.openmw.R

/** Passive overlay. Never consumes camera/movement touches. */
class VoiceHudView(context: Context) : TextView(context) {
    private var polling = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!polling) return
            val state = NativeVoice.state()
            val transmitting = state >= 0 && state and NativeVoice.TRANSMITTING != 0
            val speaking = transmitting && state and NativeVoice.SPEAKING != 0
            val names = NativeVoice.speakers()
            val local = if (transmitting) context.getString(
                if (speaking) R.string.voice_hud_speaking else R.string.voice_hud_transmitting) else ""
            val remote = if (names.isNotEmpty()) context.getString(R.string.voice_hud_players, names) else ""
            val caption = listOf(local, remote).filter { it.isNotEmpty() }.joinToString("\n")
            if (text.toString() != caption) text = caption
            alpha = if (caption.isEmpty()) 0f else 1f
            setTextColor(if (speaking) Color.rgb(156, 215, 160) else Color.rgb(229, 204, 161))
            postDelayed(this, 100)
        }
    }
    init {
        textSize = 12f
        gravity = Gravity.CENTER
        maxLines = 4
        maxWidth = (context.resources.displayMetrics.widthPixels * 0.50f).toInt()
        setTypeface(typeface, Typeface.BOLD)
        val d = context.resources.displayMetrics.density
        setPadding((12*d).toInt(), (6*d).toInt(), (12*d).toInt(), (6*d).toInt())
        background = GradientDrawable().apply {
            setColor(Color.argb(196, 27, 29, 32))
            cornerRadius = 14*d
            setStroke(maxOf(1, d.toInt()), Color.argb(128, 219, 183, 116))
        }
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setPolling(hasWindowFocus())
    }
    override fun onDetachedFromWindow() {
        setPolling(false)
        super.onDetachedFromWindow()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        setPolling(hasWindowFocus && isAttachedToWindow)
    }
    private fun setPolling(value: Boolean) {
        if (polling == value) return
        polling = value
        removeCallbacks(ticker)
        if (value) post(ticker) else { alpha = 0f; text = "" }
    }
}
