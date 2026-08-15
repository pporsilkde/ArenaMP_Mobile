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
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.preference.PreferenceManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.TextView
import org.libsdl.app.SDLActivity

// Цвета «современного» визуала клавиатуры.
// Прозрачность фона снижена на ~30% относительно исходной (0xCC -> 0x8F),
// чтобы за клавиатурой было лучше видно игру.
private const val COLOR_CONTAINER_BG = 0x8F1E1E1E.toInt()   // ~56% прозрачности
private const val COLOR_HANDLE_BG = 0xB23A3A3A.toInt()
private const val COLOR_HANDLE_GRIP = 0xFF888888.toInt()
private const val COLOR_KEY_NORMAL = 0xB22E2E2E.toInt()
private const val COLOR_KEY_PRESSED = 0xFF4A90E2.toInt()    // синий-акцент
private const val COLOR_KEY_SPECIAL = 0xB23A3A3A.toInt()    // shift/caps/enter/backspace/lang
private const val COLOR_KEY_TEXT = 0xFFEAEAEA.toInt()

/** Цвет подсветки Shift, когда тот зафиксирован (sticky). */
private const val COLOR_KEY_SHIFT_ACTIVE = 0xFFE2A44A.toInt()   // тёплый оранжевый — заметен, но не «пресс»

// Цвет «пузыря» над нажатой клавишей.
private const val COLOR_BUBBLE_BG = 0xF04A90E2.toInt()
private const val COLOR_BUBBLE_TEXT = 0xFFFFFFFF.toInt()

// Базовая альфа самих клавиш: понижена с 0.95 до 0.67 (≈ −30%).
private const val KEY_ALPHA = 0.67f
private const val KEY_ALPHA_PRESSED = 0.95f

// Скругление углов контейнера и кнопок, в dp.
private const val CORNER_CONTAINER_DP = 14f
private const val CORNER_KEY_DP = 8f

class OskTouchListener(val btn: OskButton) : View.OnTouchListener {

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                btn.pressed()
                // Для Shift, который после pressed() может оказаться в sticky,
                // НЕ перетираем альфу — её выставляет сам OskShift.updateHighlight().
                if (!(btn is OskShift && btn.sticky)) {
                    v.alpha = KEY_ALPHA_PRESSED
                }
                btn.showBubble()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                btn.released()
                // Sticky Shift должен остаться подсвеченным — альфа выше обычной.
                if (btn is OskShift && btn.sticky) {
                    v.alpha = KEY_ALPHA_PRESSED
                } else {
                    v.alpha = KEY_ALPHA
                }
                btn.hideBubble()
            }
        }
        return true
    }
}

/**
 * Создаёт фон кнопки с нормальным и нажатым состояниями и со скруглением.
 */
private fun makeKeyBackground(
    ctx: Context,
    normalColor: Int = COLOR_KEY_NORMAL,
    pressedColor: Int = COLOR_KEY_PRESSED
): StateListDrawable {
    val density = ctx.resources.displayMetrics.density
    val corner = CORNER_KEY_DP * density

    val normal = GradientDrawable().apply {
        setColor(normalColor)
        cornerRadius = corner
        setStroke(1, 0x22FFFFFF.toInt())
    }
    val pressed = GradientDrawable().apply {
        setColor(pressedColor)
        cornerRadius = corner
    }

    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }
}

/**
 * Base class for keyboard buttons.
 * Координаты и размеры — в пикселях внутри контейнера клавиатуры.
 */
abstract class OskButton(
    val text: String,
    private val positionX: Int,
    private val positionY: Int,
    private val sizeW: Int,
    private val sizeH: Int,
    private val special: Boolean = false
) {

    var view: Button? = null

    /** Всплывающий «пузырь» с символом текущей нажатой клавиши. */
    private var bubble: TextView? = null
    private var bubbleParent: RelativeLayout? = null

    fun show() { view?.visibility = View.VISIBLE }
    fun hide() {
        view?.visibility = View.GONE
        hideBubble()
    }

    /**
     * Place this button into a RelativeLayout (ожидается внутренний слой клавиатуры).
     */
    @SuppressLint("ClickableViewAccessibility")
    fun place(target: RelativeLayout) {
        val ctx = target.context
        val v = Button(ctx)
        v.transformationMethod = null
        v.text = text
        v.tag = this
        v.alpha = KEY_ALPHA
        v.visibility = View.GONE
        v.setPadding(0, 0, 0, 0)
        v.includeFontPadding = false

        val normal = if (special) COLOR_KEY_SPECIAL else COLOR_KEY_NORMAL
        v.background = makeKeyBackground(ctx, normal)
        v.setTextColor(COLOR_KEY_TEXT)
        v.textSize = (sizeH * 0.24f / ctx.resources.displayMetrics.density)
            .coerceAtLeast(10f)
        v.elevation = 2f * ctx.resources.displayMetrics.density

        val params = RelativeLayout.LayoutParams(sizeW, sizeH)
        params.leftMargin = positionX
        params.topMargin = positionY
        v.layoutParams = params

        v.setOnTouchListener(OskTouchListener(this))

        target.addView(v)
        view = v
        bubbleParent = target
    }

    fun remove(target: RelativeLayout) {
        target.removeView(view)
        hideBubble()
    }

    /**
     * Показывает всплывающий «пузырь» с подписью текущей клавиши над самой клавишей,
     * чтобы пользователь видел что именно нажал (палец всё закрывает).
     * Для служебных клавиш (shift/caps/enter/backspace/lang/стрелки) не показываем —
     * там на пузыре всё равно был бы нечитаемый символ или то же, что на самой клавише.
     */
    open fun bubbleLabel(): String? {
        if (special) return null
        return text
    }

    fun showBubble() {
        val parent = bubbleParent ?: return
        val host = view ?: return
        val label = bubbleLabel() ?: return

        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density

        val bw = (sizeW * 1.4f).toInt().coerceAtLeast((28 * density).toInt())
        val bh = (sizeH * 1.4f).toInt().coerceAtLeast((36 * density).toInt())

        val tv = bubble ?: TextView(ctx).apply {
            background = GradientDrawable().apply {
                setColor(COLOR_BUBBLE_BG)
                cornerRadius = 10f * density
                setStroke((1 * density).toInt(), 0x55FFFFFF.toInt())
            }
            setTextColor(COLOR_BUBBLE_TEXT)
            gravity = Gravity.CENTER
            textSize = (bh * 0.45f / density).coerceAtLeast(14f)
            includeFontPadding = false
            elevation = 10f * density
            isClickable = false
            isFocusable = false
        }
        tv.text = label

        val lp = RelativeLayout.LayoutParams(bw, bh)
        // Центрируем пузырь по X над клавишей и ставим над ней с небольшим зазором.
        lp.leftMargin = (positionX + (sizeW - bw) / 2).coerceAtLeast(0)
        lp.topMargin = (positionY - bh - (4 * density).toInt()).coerceAtLeast(0)
        tv.layoutParams = lp

        if (tv.parent == null) parent.addView(tv)
        tv.visibility = View.VISIBLE
        tv.bringToFront()
        bubble = tv
    }

    fun hideBubble() {
        val tv = bubble ?: return
        tv.visibility = View.GONE
    }

    open fun pressed() {}
    open fun released() {}
}

class OskSimpleButton(
    val key: Char,
    val shiftKey: Char,
    positionX: Int,
    positionY: Int,
    sizeW: Int,
    sizeH: Int
) : OskButton(key.toString(), positionX, positionY, sizeW, sizeH, special = false) {

    private val keyStr = key.toString()
    private val shiftKeyStr = shiftKey.toString()
    private var curKeyStr = keyStr

    /** Колбэк, вызываемый после ввода символа — чтобы клавиатура могла снять sticky-shift. */
    var onTyped: (() -> Unit)? = null

    override fun released() {
        SDLActivity.nativeCommitText(curKeyStr, 0)
        onTyped?.invoke()
    }

    override fun bubbleLabel(): String? = curKeyStr

    fun shift(on: Boolean) {
        curKeyStr = if (on) shiftKeyStr else keyStr
        view?.text = curKeyStr
    }
}

class OskRawButton(
    text: String,
    private val keyCode: Int,
    positionX: Int,
    positionY: Int,
    sizeW: Int,
    sizeH: Int
) : OskButton(text, positionX, positionY, sizeW, sizeH, special = true) {

    override fun pressed() {
        SDLActivity.onNativeKeyDown(keyCode)
    }

    override fun released() {
        SDLActivity.onNativeKeyUp(keyCode)
    }
}

class OskShift(
    val buttons: ArrayList<OskSimpleButton>,
    val reference: Osk,
    positionX: Int,
    positionY: Int,
    sizeW: Int,
    sizeH: Int
) : OskButton("Shift", positionX, positionY, sizeW, sizeH, special = true) {

    /** Зафиксирован ли shift до следующего нажатия буквы. */
    var sticky = false
        private set

    override fun pressed() {
        sticky = !sticky
        for (btn in buttons) btn.shift(sticky)
        updateHighlight()
        // Сообщаем клавиатуре, что sticky-shift активен — чтобы после
        // следующего ввода она автоматически его сняла.
        reference.shiftButton = this
    }

    override fun released() {
        // Ничего не делаем — sticky-режим; shift снимется либо повторным тапом
        // по самой кнопке, либо после ввода любой буквы (через clearSticky).
    }

    /** Сбросить sticky-состояние (вызывается после ввода любой буквы). */
    fun clearSticky() {
        if (!sticky) return
        sticky = false
        for (btn in buttons) btn.shift(false)
        updateHighlight()
    }

    /** Подсветить кнопку, пока sticky активен. */
    private fun updateHighlight() {
        val v = view ?: return
        val ctx = v.context
        if (sticky) {
            v.background = makeKeyBackground(
                ctx,
                normalColor = COLOR_KEY_SHIFT_ACTIVE,
                pressedColor = COLOR_KEY_PRESSED
            )
            v.alpha = KEY_ALPHA_PRESSED
        } else {
            v.background = makeKeyBackground(ctx, COLOR_KEY_SPECIAL)
            v.alpha = KEY_ALPHA
        }
    }
}

class OskCaps(
    val buttons: ArrayList<OskSimpleButton>,
    positionX: Int,
    positionY: Int,
    sizeW: Int,
    sizeH: Int
) : OskButton("Caps", positionX, positionY, sizeW, sizeH, special = true) {

    private var state = false

    override fun pressed() {
        state = !state
        for (btn in buttons) btn.shift(state)
    }
}

class OskLanguage(
    val reference: Osk,
    positionX: Int,
    positionY: Int,
    sizeW: Int,
    sizeH: Int
) : OskButton("Lang", positionX, positionY, sizeW, sizeH, special = true) {

    override fun pressed() {
        reference.changeLanguage()
    }
}

class OskEnterButton(
    private val reference: Osk,
    positionX: Int,
    positionY: Int,
    sizeW: Int,
    sizeH: Int
) : OskButton("⏎", positionX, positionY, sizeW, sizeH, special = true) {

    override fun pressed() {
        SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ENTER)
    }

    override fun released() {
        SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ENTER)
        reference.hide()
    }
}

/**
 * Плавающая экранная клавиатура. Вся раскладка лежит внутри своего контейнера,
 * который можно перемещать по экрану, перетаскивая хэндл в верхней части.
 */
class Osk {

    private var elements = ArrayList<OskButton>()
    private var visible = false
    private var russian = false

    /** Контейнер-«окно» клавиатуры; добавляется в layout игры. */
    private var container: RelativeLayout? = null

    /** Родительский layout игры. */
    private var parent: RelativeLayout? = null

    /** Фактические размеры контейнера в пикселях. */
    private var containerW = 0
    private var containerH = 0

    /** Высота хэндла (px). */
    private var handleH = 0

    /**
     * Текущая активная кнопка Shift. Используется sticky-логикой: после ввода
     * любой буквы клавиатура сама снимает sticky-shift, чтобы следующая клавиша
     * уже печаталась в нижнем регистре.
     */
    var shiftButton: OskShift? = null

    /** Вызывается каждым OskSimpleButton после ввода символа. */
    fun notifyKeyTyped() {
        shiftButton?.clearSticky()
    }

    fun placeElements(target: RelativeLayout) {
        parent = target
        target.clipChildren = false
        target.clipToPadding = false
        buildContainer(target)
    }

    /** (Пере)строить контейнер и все кнопки внутри него. */
    private fun buildContainer(target: RelativeLayout) {
        val ctx = target.context

        container?.let { target.removeView(it) }
        elements.clear()
        shiftButton = null

        val realScreenWidth = ctx.resources.displayMetrics.widthPixels
        val realScreenHeight = ctx.resources.displayMetrics.heightPixels
        val density = ctx.resources.displayMetrics.density

        // Размеры контейнера считаем напрямую от реального экрана, без привязки к
        // виртуальному 1024x768 (иначе в альбомной ориентации получаем несоразмерно
        // широкий и недостаточно высокий контейнер — часть рядов уходит за низ).
        // Берём долю экрана, одинаково работающую в portrait/landscape.
        containerW = (realScreenWidth * 0.55f).toInt().coerceAtMost((720 * density).toInt())
        containerH = (realScreenHeight * 0.45f).toInt().coerceAtMost((360 * density).toInt())
        handleH = (28 * density).toInt()

        val innerPadX = (6 * density).toInt()
        val innerPadY = (4 * density).toInt()

        // === Контейнер ===
        val cont = RelativeLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(COLOR_CONTAINER_BG)
                cornerRadius = CORNER_CONTAINER_DP * density
                setStroke((1 * density).toInt(), 0x33FFFFFF.toInt())
            }
            elevation = 8f * density
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            setOnTouchListener { _, _ -> true }
        }
        val contParams = RelativeLayout.LayoutParams(containerW, containerH + handleH)
        cont.layoutParams = contParams
        target.addView(cont)
        container = cont

        applySavedPosition(ctx)

        // === Handle ===
        val handle = TextView(ctx).apply {
            background = GradientDrawable().apply {
                setColor(COLOR_HANDLE_BG)
                val r = CORNER_CONTAINER_DP * density
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            gravity = Gravity.CENTER
            text = "⋯⋯⋯"
            setTextColor(COLOR_HANDLE_GRIP)
            textSize = 14f
        }
        val handleParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT, handleH)
        handle.layoutParams = handleParams
        cont.addView(handle)
        attachDragListener(handle, cont, target, ctx)

        // === Внутренний слой для клавиш ===
        val inner = RelativeLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            setOnTouchListener { _, _ -> true }
        }
        val innerParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            containerH
        )
        innerParams.topMargin = handleH
        inner.layoutParams = innerParams
        inner.setPadding(innerPadX, innerPadY, innerPadX, innerPadY)
        cont.addView(inner)

        layoutKeys(inner, ctx)
    }

    private fun layoutKeys(inner: RelativeLayout, ctx: Context) {
        val density = ctx.resources.displayMetrics.density

        val keyboardLayout: ArrayList<String> = if (russian) {
            arrayListOf(
                "1!2@3#4$5%6^7&8*9(0)-_=+",
                "йЙцЦуУкКеЕнНгГшШщЩзЗхХ[{]}\\|",
                "фФыЫвВаАпПрРоОлЛдДжЖэЭ;:'\"",
                "яЯчЧсСмМиИтТьЬбБюЮ,<.>/?"
            )
        } else {
            arrayListOf(
                "1!2@3#4$5%6^7&8*9(0)-_=+",
                "qQwWeErRtTyYuUiIoOpP[{]}\\|",
                "aAsSdDfFgGhHjJkKlL;:'\"",
                "zZxXcCvVbBnNmM,<.>/?"
            )
        }

        // Размеры кнопок считаем НЕ от ширины экрана, а от фактических размеров
        // контейнера — иначе в альбомной ориентации высота уходит вниз, а ширина
        // остаётся недоиспользованной (пустое место справа).
        val innerPadX = (6 * density).toInt()
        val innerPadY = (4 * density).toInt()
        val availW = (containerW - innerPadX * 2).coerceAtLeast(1)
        val availH = (containerH - innerPadY * 2).coerceAtLeast(1)

        val marginX = (2 * density).toInt().coerceAtLeast(2)
        val marginY = (2 * density).toInt().coerceAtLeast(2)

        // Cамый широкий ряд: тильда (1) + 12 символьных клавиш + Backspace (2×) = 15.
        // Берём 15 «слотов» по ширине, чтобы ничего не выходило за правую границу
        // и не наезжало друг на друга.
        val columns = 15
        val buttonWidth = ((availW - marginX * (columns - 1)) / columns).coerceAtLeast(1)

        // По высоте у нас 5 рядов: 4 ряда символов + ряд с пробелом/стрелками.
        val rows = 5
        val buttonHeight = ((availH - marginY * (rows - 1)) / rows).coerceAtLeast(1)

        val offsetX = 0
        val offsetY = 0

        // Ширины служебных клавиш слева (должны совпадать с расчётом Caps/Shift ниже).
        val capsW = (buttonWidth * 1.25f).toInt()
        val shiftW = (buttonWidth * 1.5f).toInt()

        // Смещения начала каждого ряда. Третий и четвёртый ряды обязаны
        // начинаться правее края Caps/Shift соответственно, иначе их первые
        // буквы оказываются под этими клавишами (особенно заметно на русской
        // раскладке, где ряды короче и сдвигов меньше).
        val lineOffset = intArrayOf(
            offsetX + buttonWidth,                        // ряд цифр: 1 клавиша под тильду
            offsetX + (buttonWidth * 0.35f).toInt(),      // верхний ряд букв
            offsetX + capsW + marginX,                    // ряд под Caps — строго после Caps
            offsetX + shiftW + marginX                    // нижний ряд — строго после Shift
        )

        var curY = offsetY

        val simpleButtons = ArrayList<OskSimpleButton>()
        keyboardLayout.forEachIndexed { i, line ->
            var curX = lineOffset[i]
            var j = 0
            while (j < line.length - 1) {
                val btn = OskSimpleButton(line[j], line[j + 1], curX, curY, buttonWidth, buttonHeight)
                // После ввода любой буквы сбрасываем sticky-shift, если он активен.
                btn.onTyped = { notifyKeyTyped() }
                simpleButtons.add(btn)
                curX += buttonWidth + marginX
                j += 2
            }
            curY += buttonHeight + marginY
        }
        elements.addAll(simpleButtons)

        // Shift (sticky: тап фиксирует, повторный тап снимает, ввод буквы снимает автоматически).
        val shift = OskShift(
            simpleButtons,
            this,
            offsetX,
            offsetY + 3 * (buttonHeight + marginY),
            shiftW,
            buttonHeight
        )
        elements.add(shift)
        shiftButton = shift

        // Caps
        elements.add(
            OskCaps(
                simpleButtons,
                offsetX,
                offsetY + 2 * (buttonHeight + marginY),
                capsW,
                buttonHeight
            )
        )

        // Backspace
        elements.add(
            OskRawButton(
                "⌫",
                KeyEvent.KEYCODE_DEL,
                lineOffset[0] + (buttonWidth + marginX) * keyboardLayout[0].length / 2,
                offsetY,
                buttonWidth * 2,
                buttonHeight
            )
        )

        // Enter
        elements.add(
            OskEnterButton(
                this,
                lineOffset[2] + (buttonWidth + marginX) * keyboardLayout[2].length / 2,
                offsetY + (buttonHeight + marginY) * 2,
                buttonWidth * 3,
                buttonHeight
            )
        )

        // Язык + пробел
        elements.add(
            OskLanguage(
                this,
                offsetX,
                curY,
                (buttonWidth * 1.5).toInt(),
                buttonHeight
            )
        )
        elements.add(
            OskSimpleButton(
                ' ', ' ',
                offsetX + buttonWidth * 3, curY,
                buttonWidth * 6, buttonHeight
            )
        )

        // Стрелки: уменьшены (80% от обычной клавиши), чтобы ни при какой
        // раскладке не наезжать на последнюю клавишу нижнего ряда (в русской
        // раскладке стрелка ↑ раньше перекрывала «/?»).
        val arrowW = (buttonWidth * 0.8f).toInt().coerceAtLeast(1)
        val arrowH = (buttonHeight * 0.8f).toInt().coerceAtLeast(1)
        val arrowMargin = marginX

        // Правый край последней клавиши 4-го ряда (нижнего символьного).
        val row4Keys = keyboardLayout[3].length / 2
        val row4RightEdge = lineOffset[3] + row4Keys * (buttonWidth + marginX)

        // Ставим ↑ над ←↓→ с небольшим отступом от нижнего ряда.
        var arrowsCurX = row4RightEdge + arrowMargin
        // Если вылезаем за правую границу — прижимаем к ней.
        val arrowsBlockWidth = arrowW * 3 + arrowMargin * 2
        val maxArrowX = availW - arrowsBlockWidth
        if (arrowsCurX > maxArrowX) {
            arrowsCurX = maxArrowX.coerceAtLeast(0)
        }
        // Вертикально: ↑ — на уровне 3-го ряда (чуть ниже, чтобы по центру клавиш).
        val upY = offsetY + (buttonHeight + marginY) * 3 + (buttonHeight - arrowH) / 2
        // ↑ по центру над ←↓→
        val upX = arrowsCurX + arrowW + arrowMargin
        elements.add(OskRawButton("↑", KeyEvent.KEYCODE_DPAD_UP,
            upX, upY, arrowW, arrowH))

        val bottomArrowY = offsetY + (buttonHeight + marginY) * 4 + (buttonHeight - arrowH) / 2
        elements.add(OskRawButton("←", KeyEvent.KEYCODE_DPAD_LEFT,
            arrowsCurX, bottomArrowY, arrowW, arrowH))
        elements.add(OskRawButton("↓", KeyEvent.KEYCODE_DPAD_DOWN,
            arrowsCurX + arrowW + arrowMargin, bottomArrowY, arrowW, arrowH))
        elements.add(OskRawButton("→", KeyEvent.KEYCODE_DPAD_RIGHT,
            arrowsCurX + (arrowW + arrowMargin) * 2, bottomArrowY, arrowW, arrowH))

        // Тильда
        elements.add(OskRawButton("~", 68, offsetX, offsetY, buttonWidth, buttonHeight))

        for (element in elements) element.place(inner)
    }

    /** Применить сохранённую позицию или поставить по центру внизу. */
    private fun applySavedPosition(ctx: Context) {
        val cont = container ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val savedX = prefs.getFloat("osk:pos:x", Float.NaN)
        val savedY = prefs.getFloat("osk:pos:y", Float.NaN)

        val screenW = ctx.resources.displayMetrics.widthPixels
        val screenH = ctx.resources.displayMetrics.heightPixels
        val density = ctx.resources.displayMetrics.density

        // Полная высота контейнера = клавиши + хэндл; это учтено в LayoutParams.
        val totalH = containerH + handleH

        // По умолчанию показываем клавиатуру в правом нижнем углу.
        // Как только пользователь перетянет её, дальше используются сохранённые координаты.
        val sideMargin = (16 * density)
        val bottomMargin = (16 * density)
        val defaultX = (screenW - containerW - sideMargin).coerceAtLeast(0f)
        // Ставим клавиатуру так, чтобы она целиком помещалась в экран с небольшим
        // отступом снизу (иначе нижний ряд кнопок уезжает за край).
        val defaultY = (screenH - totalH - bottomMargin).coerceAtLeast(0f)

        // Ограничиваем и сохранённые координаты — если экран/ориентация изменились,
        // старая позиция может оказаться вне видимой области.
        val maxX = (screenW - containerW).toFloat().coerceAtLeast(0f)
        val maxY = (screenH - totalH).toFloat().coerceAtLeast(0f)

        val x = if (savedX.isNaN()) defaultX else savedX.coerceIn(0f, maxX)
        val y = if (savedY.isNaN()) defaultY else savedY.coerceIn(0f, maxY)

        cont.translationX = x
        cont.translationY = y
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragListener(
        handle: View,
        cont: RelativeLayout,
        target: RelativeLayout,
        ctx: Context
    ) {
        var startRawX = 0f
        var startRawY = 0f
        var startTransX = 0f
        var startTransY = 0f
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startTransX = cont.translationX
                    startTransY = cont.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    val parentW = target.width.coerceAtLeast(containerW)
                    val parentH = target.height.coerceAtLeast(containerH + handleH)
                    val visibleHandleW = (56 * ctx.resources.displayMetrics.density)
                    val minX = -(containerW - visibleHandleW)
                    val maxX = (parentW - visibleHandleW).toFloat()
                    val minY = 0f
                    val maxY = (parentH - containerH - handleH).toFloat().coerceAtLeast(0f)
                    cont.translationX = (startTransX + dx).coerceIn(minX, maxX)
                    cont.translationY = (startTransY + dy).coerceIn(minY, maxY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit()
                        .putFloat("osk:pos:x", cont.translationX)
                        .putFloat("osk:pos:y", cont.translationY)
                        .apply()
                    true
                }
                else -> false
            }
        }
    }

    fun removeElements(target: RelativeLayout) {
        container?.let { target.removeView(it) }
        container = null
        elements.clear()
    }

    fun changeLanguage() {
        val target = parent ?: return
        russian = !russian
        val wasVisible = visible
        buildContainer(target)
        visible = false
        if (wasVisible) toggle()
    }

    fun show() {
        if (visible) return
        visible = true
        container?.visibility = View.VISIBLE
        for (element in elements) element.show()
    }

    fun hide() {
        if (!visible) return
        visible = false
        container?.visibility = View.GONE
        for (element in elements) element.hide()
    }

    fun toggle() {
        if (visible) hide() else show()
    }
}
