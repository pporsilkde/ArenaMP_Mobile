package ui.controls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import com.libopenmw.openmw.R
import ui.activity.GameActivity
import voice.NativeVoice
import voice.VoicePermissions

/** Normal OSC element: position, size and opacity use the existing controls editor. */
class OscVoiceButton : OscElement("voice_ptt", "", OscVisibility.NORMAL,
    875, 210, 82, 0.90f) {
    fun releaseInput() { (view as? VoiceButtonView)?.cancelTransmission() }
    override fun makeView(ctx: Context) {
        view = VoiceButtonView(ctx, ctx !is GameActivity).apply { tag = this@OscVoiceButton }
    }
}

private class VoiceButtonView(context: Context, private val preview: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pointer = -1
    private var state = 0
    private var running = false
    private var windowActive = false
    private val gold = Color.rgb(219, 183, 116)
    private val green = Color.rgb(156, 215, 160)
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val value = NativeVoice.state()
            if (!isShown || !hasWindowFocus() || value < 0 || value and NativeVoice.READY == 0) release()
            if (pointer >= 0) NativeVoice.press(true)
            if (state != value) { state = value; invalidate() }
            postDelayed(this, 100)
        }
    }

    init {
        isClickable = true
        isFocusable = false
        contentDescription = context.getString(R.string.voice_touch_hold)
    }
    private fun refreshPolling() {
        val shouldRun = !preview && isAttachedToWindow && isShown && windowActive
        if (running == shouldRun) return
        running = shouldRun
        removeCallbacks(ticker)
        if (running) post(ticker) else release()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        windowActive = hasWindowFocus()
        refreshPolling()
    }
    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(ticker)
        release()
        super.onDetachedFromWindow()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        windowActive = hasWindowFocus
        refreshPolling()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Can be called by View construction before Kotlin fields are initialized.
        if (isAttachedToWindow) refreshPolling()
    }
    fun cancelTransmission() { release() }
    private fun release() {
        if (pointer >= 0) NativeVoice.press(false)
        pointer = -1
        isPressed = false
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (preview) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val value = NativeVoice.state()
                val error = when {
                    value < 0 -> R.string.voice_native_update_required
                    !VoicePermissions.granted(context) -> R.string.voice_touch_permission
                    value and NativeVoice.ENABLED == 0 -> R.string.voice_touch_enable
                    value and NativeVoice.LOGGED_IN == 0 -> R.string.voice_touch_login
                    value and NativeVoice.MICROPHONE == 0 -> R.string.voice_touch_no_mic
                    value and NativeVoice.READY == 0 -> R.string.voice_touch_paused
                    else -> 0
                }
                if (error != 0) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                else {
                    pointer = event.getPointerId(event.actionIndex)
                    isPressed = true
                    NativeVoice.press(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(pointer)
                if (i < 0 || event.getX(i) < 0 || event.getY(i) < 0 ||
                    event.getX(i) >= width || event.getY(i) >= height) release()
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointer) release()
            MotionEvent.ACTION_UP -> { release(); performClick() }
            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        // TalkBack/keyboard activation explains the hold gesture; no sticky mic.
        return true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val transmitting = !preview && state >= 0 && state and NativeVoice.TRANSMITTING != 0
        val speaking = transmitting && state and NativeVoice.SPEAKING != 0
        val disabled = !preview && (state < 0 || state and NativeVoice.READY == 0)
        val color = if (speaking) green else if (disabled) Color.rgb(155, 151, 140) else gold
        val save = canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(size / 64f, size / 64f)
        paint.style = Paint.Style.FILL
        paint.color = if (transmitting) Color.argb(224, 54, 48, 35) else Color.argb(200, 28, 30, 33)
        rect.set(2f, 2f, 62f, 62f)
        canvas.drawRoundRect(rect, 20f, 20f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (transmitting) 2f else 1f
        paint.color = color
        canvas.drawRoundRect(rect, 20f, 20f, paint)
        // Vector microphone, independent of texture packs and screen density.
        paint.strokeWidth = 2.3f
        paint.strokeCap = Paint.Cap.ROUND
        rect.set(26f, 14f, 38f, 33f)
        canvas.drawRoundRect(rect, 6f, 6f, paint)
        rect.set(21f, 21f, 43f, 40f)
        canvas.drawArc(rect, 0f, 180f, false, paint)
        canvas.drawLine(32f, 40f, 32f, 45f, paint)
        canvas.drawLine(27f, 45f, 37f, 45f, paint)
        if (disabled) canvas.drawLine(20f, 15f, 44f, 43f, paint)
        val level = if (transmitting) ((state ushr 8) and 255) / 255f else 0f
        for (i in 0..4) {
            paint.color = if (level > i / 5f) color else Color.argb(65, 219, 183, 116)
            val x = 22f + i * 5f
            canvas.drawLine(x, 54f, x, 51f - if (level > i / 5f) 3f else 0f, paint)
        }
        if (transmitting) {
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawCircle(49f, 14f, 3f, paint)
        }
        canvas.restoreToCount(save)
    }
}
