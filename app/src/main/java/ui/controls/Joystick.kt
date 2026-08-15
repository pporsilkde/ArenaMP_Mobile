/*
    Copyright (C) 2018, 2019 Ilya Zhuravlev

    This file is part of OpenMW-Android.

    OpenMW-Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    OpenMW-Android is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with OpenMW-Android.  If not, see <https://www.gnu.org/licenses/>.
*/

package ui.controls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.libsdl.app.SDLActivity
import kotlin.math.hypot
import kotlin.math.min

open class Joystick : View {

    // Initial touch position
    protected var initialX = 0f
    protected var initialY = 0f

    // Current touch position
    protected var currentX = -1f
    protected var currentY = -1f

    // Whether the finger is down
    protected var down = false

    // left or right stick
    protected var stickId = 0

    // width of a stroke to draw stick circles
    private var strokeWidth = 0

    private var showVisuals = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle) {
        init()
    }

    private fun init() {
        strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1.5f, context.resources.displayMetrics
        ).toInt().coerceAtLeast(1)
        isClickable = true
        isFocusable = false
    }

    fun setStick(id: Int) {
        stickId = id
    }

    fun setShowVisuals(show: Boolean) {
        showVisuals = show
        invalidate()
    }

    /**
     * The joystick views intentionally cover a large half-screen touch field. In V8
     * they are brought above the OSC buttons so a camera/movement drag which starts
     * next to a button remains owned by the stick even after crossing that button.
     *
     * For a real button press we still pass ACTION_DOWN through to the control below.
     * A small inset keeps the transparent outer pixels of PNG buttons usable as stick
     * space instead of creating a surprisingly large dead rectangle around the icon.
     */
    private fun shouldPassThroughToButton(event: MotionEvent): Boolean {
        val group = parent as? ViewGroup ?: return false
        val rawX = event.rawX
        val rawY = event.rawY
        val location = IntArray(2)

        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child === this || child is Joystick || child.visibility != View.VISIBLE)
                continue

            // OSC controls tag themselves with OscElement. Ignore SDL surface and other
            // non-control views; the stick should remain a large gameplay touch field.
            if (child.tag !is OscElement)
                continue

            child.getLocationOnScreen(location)
            val insetX = child.width * 0.08f
            val insetY = child.height * 0.08f
            val left = location[0] + insetX
            val top = location[1] + insetY
            val right = location[0] + child.width - insetX
            val bottom = location[1] + child.height - insetY

            if (rawX >= left && rawX <= right && rawY >= top && rawY <= bottom)
                return true
        }
        return false
    }

    private fun idleCenterX(): Float {
        // Both values are local to their true half-screen view. Keep them mirrored
        // around the screen centre: left at 34%, right at 62% of its half.
        return width * if (stickId == 0) 0.34f else 0.62f
    }

    private fun idleCenterY(): Float = height * 0.73f

    public override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!showVisuals || width <= 0 || height <= 0)
            return
        // Do not draw the stick hint over inventory/dialogue cursor mode. The touch
        // field can remain alive for compatibility, but the HUD stays visually clean.
        if (SDLActivity.isMouseShown() != 0)
            return

        val baseX = if (down) initialX else idleCenterX()
        val baseY = if (down) initialY else idleCenterY()
        val baseRadius = (min(width, height) * 0.085f).coerceIn(34f, 72f)
        val thumbRadius = baseRadius * 0.43f

        var thumbX = baseX
        var thumbY = baseY
        if (down) {
            val dx = currentX - baseX
            val dy = currentY - baseY
            val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val maxVisualTravel = baseRadius * 0.72f
            val scale = if (distance > maxVisualTravel && distance > 0f) maxVisualTravel / distance else 1f
            thumbX += dx * scale
            thumbY += dy * scale
        }

        // Soft translucent base: visible enough to find by peripheral vision but
        // deliberately much lighter than the action buttons.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(if (down) 54 else 34, 26, 30, 34)
        canvas.drawCircle(baseX, baseY, baseRadius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth.toFloat()
        paint.color = Color.argb(if (down) 112 else 72, 225, 230, 235)
        canvas.drawCircle(baseX, baseY, baseRadius, paint)

        // "Mushroom" cap.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(if (down) 118 else 78, 205, 212, 218)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth.toFloat()
        paint.color = Color.argb(if (down) 150 else 96, 245, 248, 250)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, paint)

        // Tiny centre dimple makes the cap readable without adding a texture/resource.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(if (down) 96 else 62, 70, 76, 82)
        canvas.drawCircle(thumbX, thumbY, thumbRadius * 0.26f, paint)
    }

    public override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(widthMeasureSpec, heightMeasureSpec)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && shouldPassThroughToButton(event))
            return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                initialX = event.x
                initialY = event.y
                currentX = initialX
                currentY = initialY
                down = true
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                down = false
                currentY = -1f
                currentX = -1f
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP)
                    performClick()
            }
        }

        updateStick()
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    protected open fun updateStick() {}
}
