/*
    Кнопка, совмещающая два режима. Стоит в ЛЕВОМ ВЕРХНЕМ углу
    под колесом прокрутки, прижата к левому краю и выровнена по X с ESC/паузой.
    размер — как у обычных osc-кнопок. Между режимами меняется только
    иконка, альфа и наличие пульсации:

    1. KEYBOARD — активно поле ввода (SDL вызвал showTextInput).
       Иконка keyboard.png, кнопка слабо пульсирует альфой, по тапу
       показывает/скрывает экранную клавиатуру (Osk).

    2. POSTPROCESS — поля ввода нет.
       Иконка postprocessing.png, статичная альфа 0.3 как у osc-кнопок.
       Двойной тап — F11, долгое удержание — F12 + вспышка-рамка.

    При исчезновении поля ввода — сама возвращается в режим POSTPROCESS.
    Если требуется полностью скрыть — вызвать hide().
 */

package ui.controls

import android.animation.AnimatorListenerAdapter
import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.RelativeLayout
import com.libopenmw.openmw.R
import org.libsdl.app.SDLActivity

/** Пороговое время в мс для обнаружения long-press в режиме POSTPROCESS. */
private const val LONG_PRESS_MS = 500L

/** Максимальный интервал между двумя тапами для F11. */
private const val DOUBLE_TAP_MS = 350L

/** Виртуальная система координат, как в Osc.kt. */
private const val VU_W = 1024
private const val VU_H = 768

/**
 * Позиция и размер в POSTPROCESS-режиме (виртуальные единицы).
 * Колесо прокрутки находится около Y=450. Кнопка идёт сразу под ним и
 * выровнена по X=12 с верхней кнопкой ESC/паузы.
 * Размер = CONTROL_DEFAULT_SIZE (65) — как у обычных osc-кнопок.
 */
private const val POST_VU_X = 12
private const val POST_VU_Y = 528
private const val POST_VU_SIZE = 65   // == CONTROL_DEFAULT_SIZE
private const val POST_ALPHA = 0.3f   // как у обычных osc-кнопок

/**
 * Режимы работы кнопки.
 */
enum class KeyboardToggleMode {
    /** Активно поле ввода — кнопка пульсирует, иконка keyboard. */
    KEYBOARD,
    /** Поля ввода нет — кнопка статична, иконка postprocessing, F12/F11. */
    POSTPROCESS
}

class KeyboardToggleButton(
    private val onToggleKeyboard: () -> Unit
) {

    private var view: ImageView? = null
    private var parent: RelativeLayout? = null
    private var mode: KeyboardToggleMode = KeyboardToggleMode.POSTPROCESS
    private var pulseAnimator: ValueAnimator? = null

    private val handler = Handler(Looper.getMainLooper())
    private var longPressFired = false
    private var firstTapAt = 0L
    private val resetTapRunnable = Runnable { firstTapAt = 0L }
    private val longPressRunnable = Runnable {
        longPressFired = true
        firstTapAt = 0L
        handler.removeCallbacks(resetTapRunnable)
        // Долгое удержание — F12 + вспышка-рамка, как при срабатывании затвора.
        SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_F12)
        SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_F12)
        flashScreenshotBorder()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun placeInto(target: RelativeLayout) {
        parent = target
        val ctx = target.context

        val v = ImageView(ctx).apply {
            setImageResource(R.drawable.postprocessing)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = false
        }

        // Начальные layout params; реальные размеры и позиция выставятся
        // в applyMode() ниже в зависимости от текущего режима.
        v.layoutParams = RelativeLayout.LayoutParams(0, 0)

        v.setOnTouchListener { view, event -> handleTouch(view, event) }

        target.addView(v)
        view = v

        // Пересчитываем позицию/размер при каждом изменении размера экрана
        // (ротация, resize, появление/исчезновение панелей). Важно: при самом
        // первом layout pass old=0,0,0,0, new=реальные размеры — тоже нужно
        // пересчитаться, поэтому сравниваем размеры old vs new, а не только
        // «поменялось ли что-то».
        target.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            val oldW = or - ol
            val oldH = ob - ot
            val newW = r - l
            val newH = b - t
            if (newW != oldW || newH != oldH) updateLayout()
        }

        applyMode(KeyboardToggleMode.POSTPROCESS)
    }

    /**
     * Обновить LayoutParams: кнопка стоит под колесом прокрутки у левого края
     * (виртуальные координаты POST_VU_X / POST_VU_Y, размер POST_VU_SIZE).
     * Различие между режимами —
     * только иконка/альфа/пульсация, а не позиция.
     */
    private fun updateLayout() {
        val v = view ?: return
        val target = parent ?: return
        val ctx = v.context

        // Пересчёт виртуальных координат в пиксели — один в один как в Osc.kt
        // (см. OscElement.updateView): реальные ширина/высота родителя,
        // поделённые на VU_W/VU_H.
        val parentW = target.width.takeIf { it > 0 }
            ?: ctx.resources.displayMetrics.widthPixels
        val parentH = target.height.takeIf { it > 0 }
            ?: ctx.resources.displayMetrics.heightPixels

        val realX = POST_VU_X * parentW / VU_W
        val realY = POST_VU_Y * parentH / VU_H
        val realW = POST_VU_SIZE * parentW / VU_W
        val realH = POST_VU_SIZE * parentH / VU_H

        val lp = RelativeLayout.LayoutParams(realW, realH)
        lp.leftMargin = realX
        lp.topMargin = realY
        v.layoutParams = lp

        // Альфу ведём отдельно: в KEYBOARD её анимирует pulseAnimator,
        // в POSTPROCESS — фиксированное POST_ALPHA.
        if (mode == KeyboardToggleMode.POSTPROCESS) {
            v.alpha = POST_ALPHA
        }
    }

    private fun handleTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().scaleX(0.92f).scaleY(0.92f).alpha(1.0f).setDuration(90).start()
                if (mode == KeyboardToggleMode.POSTPROCESS) {
                    longPressFired = false
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                restoreIdleAlpha(v)

                when (mode) {
                    KeyboardToggleMode.KEYBOARD -> {
                        onToggleKeyboard()
                    }
                    KeyboardToggleMode.POSTPROCESS -> {
                        handler.removeCallbacks(longPressRunnable)
                        if (!longPressFired) {
                            // F11 только по двойному тапу. Первый тап ничего не
                            // отправляет, поэтому случайное касание не переключает режим.
                            val now = android.os.SystemClock.uptimeMillis()
                            if (firstTapAt != 0L && now - firstTapAt <= DOUBLE_TAP_MS) {
                                firstTapAt = 0L
                                handler.removeCallbacks(resetTapRunnable)
                                SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_F11)
                                SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_F11)
                            } else {
                                firstTapAt = now
                                handler.removeCallbacks(resetTapRunnable)
                                handler.postDelayed(resetTapRunnable, DOUBLE_TAP_MS)
                            }
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                restoreIdleAlpha(v)
                handler.removeCallbacks(longPressRunnable)
                return true
            }
        }
        return false
    }

    /** Плавно вернуть альфу в состояние покоя для текущего режима. */
    private fun restoreIdleAlpha(v: View) {
        when (mode) {
            KeyboardToggleMode.KEYBOARD -> {
                // В режиме клавиатуры альфу ведёт pulseAnimator — достаточно
                // сбросить масштаб, пульс сам продолжит переливаться.
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
            KeyboardToggleMode.POSTPROCESS -> {
                v.animate().scaleX(1.0f).scaleY(1.0f).alpha(POST_ALPHA).setDuration(120).start()
            }
        }
    }

    fun setMode(newMode: KeyboardToggleMode) {
        if (mode == newMode) return
        applyMode(newMode)
    }

    private fun applyMode(newMode: KeyboardToggleMode) {
        mode = newMode
        firstTapAt = 0L
        handler.removeCallbacks(resetTapRunnable)
        val v = view ?: return
        when (newMode) {
            KeyboardToggleMode.KEYBOARD -> {
                v.setImageResource(R.drawable.keyboard)
                updateLayout()
                startPulse()
            }
            KeyboardToggleMode.POSTPROCESS -> {
                v.setImageResource(R.drawable.postprocessing)
                stopPulse()
                updateLayout()
            }
        }
    }

    private fun startPulse() {
        val v = view ?: return
        stopPulse()
        val anim = ValueAnimator.ofFloat(0.55f, 1.0f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { a -> v.alpha = a.animatedValue as Float }
        }
        anim.start()
        pulseAnimator = anim
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    /**
     * Короткая вспышка аккуратной рамки по периметру экрана — визуальное
     * подтверждение «сработал F12» (например, сохранение скриншота игрой).
     * Рамка — прозрачная внутри, белая обводка с небольшим скруглением;
     * плавно появляется и тут же исчезает, не блокируя ввод.
     */
    private fun flashScreenshotBorder() {
        val target = parent ?: return
        val ctx = target.context
        val density = ctx.resources.displayMetrics.density

        val border = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke((4 * density).toInt(), 0xFFFFFFFF.toInt())
                cornerRadius = 12f * density
            }
            // Не перехватывать касания — игра/UI должны продолжать работать.
            isClickable = false
            isFocusable = false
            alpha = 0f
            elevation = 20f * density
        }
        val lp = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        border.layoutParams = lp
        target.addView(border)
        border.bringToFront()

        val anim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 420
            interpolator = LinearInterpolator()
            addUpdateListener { a -> border.alpha = (a.animatedValue as Float).coerceAtMost(0.9f) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    (border.parent as? ViewGroup)?.removeView(border)
                }
            })
        }
        anim.start()
    }

    fun show() {
        view?.visibility = View.VISIBLE
    }

    fun hide() {
        view?.visibility = View.GONE
        stopPulse()
    }

    fun remove() {
        stopPulse()
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(resetTapRunnable)
        firstTapAt = 0L
        val v = view ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        view = null
    }
}
