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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.preference.PreferenceManager
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RelativeLayout
import com.libopenmw.openmw.R
import org.libsdl.app.SDLActivity
import ui.activity.GameActivity
import ui.activity.MouseMode

import android.view.GestureDetector
import android.view.View.OnTouchListener
import android.graphics.BitmapFactory
import constants.Constants
import java.io.File

import java.lang.Math

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

const val VIRTUAL_SCREEN_WIDTH = 1024
const val VIRTUAL_SCREEN_HEIGHT = 768
const val CONTROL_DEFAULT_SIZE = 65
const val JOYSTICK_SIZE = 200
const val JOYSTICK_OFFSET = 110
const val TOP_BAR_SPACING = 90



/**
 * Class to hold on-screen control elements such as buttons or joysticks.
 * The position, opacity and size can be customized by the user.
 *
 * @param uniqueId a string identifying the element, used to store customized positions
 * @param defaultX: default X position of the element
 * @param defaultY: default Y position of the element
 * @param defaultSize: default size (both width and height) of the element
 * @param defaultOpacity: default opacity of the element
 */
open class OscElement(
        val uniqueId: String,
        val iconName: String,
        var visibility: OscVisibility,
        val defaultX: Int,
        val defaultY: Int,
        private val defaultSize: Int = CONTROL_DEFAULT_SIZE,
        private val defaultOpacity: Float = 0.3f,
        val configurable: Boolean = true
) {

    private var opacity = defaultOpacity
    var size = defaultSize
    var x = defaultX
    var y = defaultY

    var view: View? = null

    /**
     * Creates a View object for this element.
     * The object should have a custom OnTouchListener which performs the desired action.
     *
     * @param ctx Android Context, comes from layout.
     */
    open fun makeView(ctx: Context) {
        val v = ImageView(ctx)
        v.setBackgroundColor(Color.RED)
        v.tag = this
        view = v
    }

    /**
     * Creates and places this element into a RelativeLayout.
     *
     * @param target RelativeLayout to put the new element into.
     */
    fun place(target: RelativeLayout) {
        makeView(target.context)
        val v = view ?: return

        target.addView(v)
        updateView()
    }

    fun placeConfigurable(target: RelativeLayout, listener: View.OnTouchListener) {
        place(target)
        if (configurable) {
            view?.setOnTouchListener(listener)
        }
        view?.visibility = View.VISIBLE
    }

    fun changeOpacity(delta: Float) {
        if (!configurable) return
        opacity = Math.max(0f, Math.min(opacity + delta, 1.0f))
        savePrefs()
    }

    fun changeSize(delta: Int) {
        if (!configurable) return
        size = Math.max(0, size + delta)
        savePrefs()
    }

    fun changePosition(virtualX: Int, virtualY: Int) {
        if (!configurable) return
        x = virtualX
        y = virtualY
        savePrefs()
    }

    open fun getVirtualWidth(): Int = size

    open fun getVirtualHeight(): Int = size

    // Regular buttons use one uniform scale so square icons stay square. Large
    // transparent joystick hit fields are different: they must span their real
    // half of the physical screen, including ultrawide phones.
    open fun useIndependentAxisScale(): Boolean = false

    fun updateView() {
        val v = view ?: return

        val realScreenWidth = (v.parent as View).width
        val realScreenHeight = (v.parent as View).height

        // Единый масштаб — меньший из двух осей, чтобы кнопки оставались квадратными
        // и картинки на них не растягивались
        val scaleX = realScreenWidth.toDouble() / VIRTUAL_SCREEN_WIDTH
        val scaleY = realScreenHeight.toDouble() / VIRTUAL_SCREEN_HEIGHT
        val uniformScale = minOf(scaleX, scaleY)

        val realX = (x * scaleX).toInt()
        val realY = (y * scaleY).toInt()

        val screenWidth = if (useIndependentAxisScale())
            (getVirtualWidth() * scaleX).toInt()
        else
            (getVirtualWidth() * uniformScale).toInt()
        val screenHeight = if (useIndependentAxisScale())
            (getVirtualHeight() * scaleY).toInt()
        else
            (getVirtualHeight() * uniformScale).toInt()
        val params = RelativeLayout.LayoutParams(screenWidth, screenHeight)

        params.leftMargin = realX
        params.topMargin = realY

        v.layoutParams = params

        v.alpha = opacity
    }

    private fun savePrefs() {
        if (!configurable) return
        val v = view ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(v.context)
        with (prefs.edit()) {
            putFloat("osc:$uniqueId:opacity", opacity)
            putInt("osc:$uniqueId:size", size)
            putInt("osc:$uniqueId:x", x)
            putInt("osc:$uniqueId:y", y)

            commit()
        }
    }

    fun loadPrefs(ctx: Context) {
        if (!configurable) {
            opacity = defaultOpacity
            size = defaultSize
            x = defaultX
            y = defaultY
            updateView()
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

        opacity = prefs.getFloat("osc:$uniqueId:opacity", defaultOpacity)
        size = prefs.getInt("osc:$uniqueId:size", defaultSize)
        x = prefs.getInt("osc:$uniqueId:x", defaultX)
        y = prefs.getInt("osc:$uniqueId:y", defaultY)

        updateView()
    }

    fun resetPrefs(ctx: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

        with (prefs.edit()) {
            remove("osc:$uniqueId:opacity")
            remove("osc:$uniqueId:size")
            remove("osc:$uniqueId:x")
            remove("osc:$uniqueId:y")

            commit()
        }

        loadPrefs(ctx)
    }
}

class OscImageButton(
        uniqueId: String,
        iconName: String,
        visibility: OscVisibility,
        private val imageSrc: Int,
        defaultX: Int,
        defaultY: Int,
        private val keyCode: Int,
        private val needMouse: Boolean = false,
        defaultSize: Int = CONTROL_DEFAULT_SIZE,
        defaultOpacity: Float = 0.3f,
        private val togglable: Boolean = false
) : OscElement(uniqueId, iconName, visibility, defaultX, defaultY, defaultSize, defaultOpacity) {

    override fun makeView(ctx: Context) {
        val v = ImageView(ctx)

        if (File(Constants.USER_FILE_STORAGE + "/launcher/icons/" + iconName).exists())
            v.setImageBitmap(BitmapFactory.decodeFile(Constants.USER_FILE_STORAGE + "/launcher/icons/" + iconName))
        else
            v.setImageResource(imageSrc)

/*
        val shape = GradientDrawable()
        shape.setShape(GradientDrawable.RECTANGLE)
        shape.setColor(Color.TRANSPARENT)
        shape.setCornerRadius(0.0f)
        v.setBackground(shape)
*/

        // fix blurry icons on old android
        v.scaleType = ImageView.ScaleType.FIT_XY
        v.setOnTouchListener(ButtonTouchListener(keyCode, needMouse, togglable))
        v.tag = this

        view = v
    }

}

class OscCustomButton(
    uniqueId: String,
    iconName: String,
    visibility: OscVisibility,
    private val imageSrc: Int,
    defaultX: Int,
    defaultY: Int,
    private val handler: () -> Unit
) : OscElement(uniqueId, iconName, visibility, defaultX, defaultY) {

    @SuppressLint("ClickableViewAccessibility")
    override fun makeView(ctx: Context) {
        val v = ImageView(ctx)

        if (File(Constants.USER_FILE_STORAGE + "/launcher/icons/" + iconName).exists())
            v.setImageBitmap(BitmapFactory.decodeFile(Constants.USER_FILE_STORAGE + "/launcher/icons/" + iconName))
        else
            v.setImageResource(imageSrc)

        v.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> view.animate().alpha(0.42f).scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                MotionEvent.ACTION_UP -> {
                    view.animate().alpha(0.3f).scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    handler()
                }
                MotionEvent.ACTION_CANCEL -> view.animate().alpha(0.3f).scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
            true
        }
        v.tag = this

        view = v
    }

}

/**
 * Кнопка с поддержкой long-press. Есть два независимых слоя поведения:
 *
 *  1) [holdKey] — если задан, клавиша жмётся (onNativeKeyDown) в момент
 *     ACTION_DOWN и отпускается (onNativeKeyUp) в ACTION_UP/CANCEL. Это
 *     нужно для игровых биндов, где зажатие = длящееся действие (плавание
 *     вверх при зажатом space, удержание блока и т.п.). В игре такая
 *     кнопка ведёт себя ровно как обычная OscImageButton с ButtonTouchListener.
 *
 *  2) [longPressHandler] — если пользователь продержал палец дольше
 *     [longPressMs], дополнительно стреляет эта лямбда. На зажатый
 *     holdKey это НЕ влияет: клавиша продолжает держаться до отпускания.
 *
 *  3) [shortHandler] — вызывается по ACTION_UP, ТОЛЬКО если long-press не
 *     сработал (то есть палец отпустили быстрее longPressMs). Удобно для
 *     кнопок без holdKey, где короткий тап = одно действие, удержание = другое
 *     (пример: quickSave — короткий тап открывает диалог сохранения,
 *     удержание стреляет F2).
 *
 * Визуально во время удержания кнопка «прижата» (scale 0.94, alpha 0.42),
 * а в момент срабатывания long-press — короткий «поп» (увеличение 1.02 +
 * полная альфа), чтобы было понятно, что команда сработала.
 */
class OscLongPressButton(
    uniqueId: String,
    iconName: String,
    visibility: OscVisibility,
    private val imageSrc: Int,
    defaultX: Int,
    defaultY: Int,
    private val longPressMs: Long,
    private val longPressHandler: () -> Unit,
    private val shortHandler: (() -> Unit)? = null,
    private val holdKey: Int? = null
) : OscElement(uniqueId, iconName, visibility, defaultX, defaultY) {

    @SuppressLint("ClickableViewAccessibility")
    override fun makeView(ctx: Context) {
        val v = ImageView(ctx)

        if (File(Constants.USER_FILE_STORAGE + "/launcher/icons/" + iconName).exists())
            v.setImageBitmap(BitmapFactory.decodeFile(Constants.USER_FILE_STORAGE + "/launcher/icons/" + iconName))
        else
            v.setImageResource(imageSrc)

        v.scaleType = ImageView.ScaleType.FIT_XY

        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var longFired = false
        var holdActive = false
        val longRunnable = Runnable {
            longFired = true
            longPressHandler()
            // Маленький «поп» — подтверждаем, что сработало долгое удержание.
            // Если holdKey активен (клавиша всё ещё зажата), возвращаемся к
            // «прижатой» визуалке; иначе — к idle-состоянию.
            val idleAlpha = if (holdActive) 0.42f else 0.3f
            val idleScale = if (holdActive) 0.94f else 1.0f
            v.animate().alpha(1.0f).scaleX(1.02f).scaleY(1.02f).setDuration(80)
                .withEndAction {
                    v.animate().alpha(idleAlpha).scaleX(idleScale).scaleY(idleScale).setDuration(160).start()
                }
                .start()
        }

        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    longFired = false
                    view.animate().alpha(0.42f).scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                    h.postDelayed(longRunnable, longPressMs)
                    // Зажимаем клавишу сразу — игре это нужно для длящихся
                    // действий (плавание, подъём вверх и т.п.).
                    holdKey?.let {
                        SDLActivity.onNativeKeyDown(it)
                        holdActive = true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    h.removeCallbacks(longRunnable)
                    // Отпускаем зажатую клавишу в первую очередь — чтобы игра
                    // корректно завершила текущее действие.
                    holdKey?.let {
                        if (holdActive) {
                            SDLActivity.onNativeKeyUp(it)
                            holdActive = false
                        }
                    }
                    view.animate().alpha(0.3f).scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    if (!longFired) shortHandler?.invoke()
                }
                MotionEvent.ACTION_CANCEL -> {
                    h.removeCallbacks(longRunnable)
                    holdKey?.let {
                        if (holdActive) {
                            SDLActivity.onNativeKeyUp(it)
                            holdActive = false
                        }
                    }
                    view.animate().alpha(0.3f).scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
            }
            true
        }
        v.tag = this

        view = v
    }

}

class OscGestureButton(
        uniqueId: String,
        iconName: String,
        visibility: OscVisibility,
        private val imageSrc: Int,
        defaultX: Int,
        defaultY: Int,
        defaultSize: Int = CONTROL_DEFAULT_SIZE,
        private val mouseScroll: Boolean,
        private val pressKeyCode: Int,
        private val leftKeyCode: Int,
        private val rightKeyCode: Int,
        private val upKeyCode: Int,
        private val downKeyCode: Int,
        private val longPressKeyCode: Int = 0,
        private val longPressMs: Long = 650L
) : OscElement(uniqueId, iconName, visibility, defaultX, defaultY, defaultSize) {

    override fun makeView(ctx: Context) {
        val v = ImageView(ctx)

        if (File(Constants.USER_FILE_STORAGE + "/launcher/icons/" + iconName).exists())
            v.setImageBitmap(BitmapFactory.decodeFile(Constants.USER_FILE_STORAGE + "/launcher/icons/" + iconName))
        else
            v.setImageResource(imageSrc)

        // fix blurry icons on old android
        v.scaleType = ImageView.ScaleType.FIT_XY
        v.setOnTouchListener(GestureButtonTouchListener(
            ctx, mouseScroll, pressKeyCode, leftKeyCode, rightKeyCode, upKeyCode, downKeyCode,
            longPressKeyCode, longPressMs))
        v.tag = this
        view = v
    }
}

class OscJoystickLeft(
    uniqueId: String,
    visibility: OscVisibility,
    defaultX: Int,
    defaultY: Int,
    defaultSize: Int,
    private val fieldWidth: Int,
    private val fieldHeight: Int,
    private val stick: Int
) : OscElement(uniqueId, "", visibility, defaultX, defaultY, defaultSize, 1.0f, false) {

    override fun makeView(ctx: Context) {
        val v = JoystickLeft(ctx)
        v.setStick(stick)
        v.setShowVisuals(false)
        v.tag = this

        view = v
    }

    override fun getVirtualWidth(): Int = fieldWidth
    override fun getVirtualHeight(): Int = fieldHeight
    override fun useIndependentAxisScale(): Boolean = true
}

class OscJoystickRight(
    uniqueId: String,
    visibility: OscVisibility,
    defaultX: Int,
    defaultY: Int,
    defaultSize: Int,
    private val fieldWidth: Int,
    private val fieldHeight: Int,
    private val stick: Int
) : OscElement(uniqueId, "", visibility, defaultX, defaultY, defaultSize, 1.0f, false) {

    override fun makeView(ctx: Context) {
        val v = JoystickRight(ctx)
        v.setStick(stick)
        v.setShowVisuals(false)
        v.tag = this

        view = v
    }

    override fun getVirtualWidth(): Int = fieldWidth
    override fun getVirtualHeight(): Int = fieldHeight
    override fun useIndependentAxisScale(): Boolean = true
}

open class OscHiddenButton(
    uniqueId: String,
    visibility: OscVisibility,
    defaultX: Int,
    defaultY: Int,
    private val title: String,
    private val keyCode: Int,
    defaultSize: Int = CONTROL_DEFAULT_SIZE,
    defaultOpacity: Float = 0.3f,
    private val togglable: Boolean = false,
    private val radius: Float = 25.0f
) : OscElement(uniqueId, "", visibility, defaultX, defaultY, defaultSize - 10, defaultOpacity) {

    override fun makeView(ctx: Context) {
        val v = Button(ctx)
        v.tag = this
        v.setOnTouchListener(ButtonTouchListener(keyCode, false, togglable))
        v.text = title
        v.visibility = View.GONE

        v.setPadding(0, 0, 0, 0)
        val shape = GradientDrawable()
        shape.setShape(GradientDrawable.RECTANGLE)
        shape.setColor(Color.GRAY)
        shape.setCornerRadius(radius)
        v.setBackground(shape)

        view = v
    }

}

class OscQuickKeysToggle(
    uniqueId: String,
    visibility: OscVisibility,
    defaultX: Int,
    defaultY: Int,
    title: String,
    private val buttons: ArrayList<OscHiddenButton>
) : OscHiddenButton(uniqueId, visibility, defaultX, defaultY, title, 0, CONTROL_DEFAULT_SIZE, 0.0f) {

    override fun makeView(ctx: Context) {
        super.makeView(ctx)
        view?.setOnTouchListener(QuickKeysButtonTouchListener(ctx, buttons))
        view?.visibility = View.VISIBLE
    }

}
enum class OscVisibility(val v: Int) {
    // Mark as should not be touched by osc-visibility handling
    NULL(0),
    // Widgets that must be visible when menu is open
    ESSENTIAL(1),
    // Widgets visible during gameplay
    NORMAL(2)
}

class Osc {
    private var osk = Osk()
    var keyboardVisible = false //< Mode where only keyboard is visible
    var mouseVisible = false //< Mode where only mouse-switch icon is visible
    private var topVisible = true //< The controls located at the top hidden behind the hamburger toggle
    private var visibilityState = 0

    /**
     * Угловая кнопка: при активном вводе показывает экранную клавиатуру,
     * иначе работает как F11/F12 (двойной тап / удержание).
     */
    private val kbdToggle = KeyboardToggleButton { toggleKeyboard() }


    private val btnMouse = OscCustomButton("mouse", "mouse.png", OscVisibility.NULL,
        R.drawable.mouse, TOP_BAR_SPACING * 7, 0) { toggleMouse() }

    private val joystickLeft = OscJoystickLeft("joystickLeft", OscVisibility.NORMAL,
        0, 0, JOYSTICK_SIZE, VIRTUAL_SCREEN_WIDTH / 2, VIRTUAL_SCREEN_HEIGHT, 0)
    private val joystickRight = OscJoystickRight("joystickRight", OscVisibility.ESSENTIAL,
        VIRTUAL_SCREEN_WIDTH / 2, 0, JOYSTICK_SIZE, VIRTUAL_SCREEN_WIDTH / 2, VIRTUAL_SCREEN_HEIGHT, 1)


    private var elements = arrayListOf(
        joystickLeft,
        joystickRight,

        // Scroll wheel keeps normal mouse-wheel gestures. Holding it sends TAB once.
        OscGestureButton("scroll_wheel", "scroll_wheel.png", OscVisibility.ESSENTIAL,
            R.drawable.scroll_wheel, 0, 450, CONTROL_DEFAULT_SIZE, true, 0, 0, 0, 0, 0,
            KeyEvent.KEYCODE_TAB, 650L),
        OscImageButton("crouch", "sneak.png", OscVisibility.NORMAL,
            R.drawable.sneak, 100, 650, 113),
        OscImageButton("pause_top_left", "pause.png", OscVisibility.ESSENTIAL,
            R.drawable.pause, 12, 12, KeyEvent.KEYCODE_ESCAPE,
            defaultOpacity = 0.52f),
        OscImageButton("inventory", "inventory.png", OscVisibility.NULL,
            R.drawable.inventory, 965, 290, 3, true),
        // Save button: short tap sends Y, long press sends F2.
        OscLongPressButton("wait", "save.png", OscVisibility.NULL,
            R.drawable.save, 965, 380, 650L,
            longPressHandler = { sendKey(KeyEvent.KEYCODE_F2) },
            shortHandler = { sendKey(KeyEvent.KEYCODE_Y) }),
        OscImageButton("magic", "toggle_magic.png", OscVisibility.NORMAL,
            R.drawable.toggle_magic, 965, 470, KeyEvent.KEYCODE_R),
        OscImageButton("weapon", "toggle_weapon.png", OscVisibility.NORMAL,
            R.drawable.toggle_weapon, 965, 560, KeyEvent.KEYCODE_F),
        OscImageButton("journal", "journal.png", OscVisibility.NORMAL,
            R.drawable.journal, 965, 650, KeyEvent.KEYCODE_J),
        OscAttackButton("fire", "attack.png", OscVisibility.ESSENTIAL,
            R.drawable.attack, 800, 315, 1, 120),
        OscImageButton("use", "use.png", OscVisibility.NORMAL,
            R.drawable.use,  300, 650, KeyEvent.KEYCODE_E),
        // Jump: пробел зажат всё время касания (плавание вверх работает),
        // удержание 1000мс — дополнительно стреляет Q. Зажатие пробела
        // при этом не прерывается — пальцем продолжаем держать, пока нужно.
        OscLongPressButton("jump", "jump.png", OscVisibility.NORMAL,
            R.drawable.jump, 650, 650, 1000L,
            longPressHandler = { sendKey(KeyEvent.KEYCODE_Q) },
            holdKey = KeyEvent.KEYCODE_SPACE)
    )

    private val topButtons: ArrayList<OscElement>
    private val quickButtons = arrayListOf<OscHiddenButton>()
    private val qp: OscQuickKeysToggle

    init {
        // create controls.cfg
        val launcherDir = File(Constants.USER_FILE_STORAGE + "/launcher")
        val iconsDir = File(Constants.USER_FILE_STORAGE + "/launcher/icons")
        launcherDir.mkdirs()
        iconsDir.mkdirs()
        if (!File(Constants.USER_FILE_STORAGE + "/launcher/controls.cfg").exists()) {
            File(Constants.USER_FILE_STORAGE + "/launcher/controls.cfg").writeText(
"//syntax: Key or keycode; button text or image to load; default x; default y; visibility; default size; default alpha; togglable; rounding\n\nKey can be single string as w s a d etc or android keycode\nList of android keycodes www.temblast.com/ref/akeyscode.htm\nIcons are loaded from icons folder, icon names cant contain spaces, if not found it use simple button with specified text\nDefault x and default y specify default position of added button in 1024x728 grid, can be changed in app later\nVisibility 0 mean button is not visible in menus, 1 means always visible\nDefault button size original is 70\nDefault button alpha original is 0.4\nTogglable specify if button is togglable, press once to activate, deactivate on second press\n Rounding specify rounding of corners for simple buttons 0.0 = square 100.0 = circle\n")

            File(Constants.USER_FILE_STORAGE + "/launcher/controls-example.cfg").writeText(
"p; phys ;682; 100; 0; 70; 0.4; 1; 25.0\nb; block; 382; 100; 0; 70; 0.4; 1; 50.0\nr; run; 382; 300; 0; 70; 0.4; 0; 100.0")
        }

        val mKeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        File(Constants.USER_FILE_STORAGE + "/launcher/controls.cfg").readLines().forEach {
            val customButton: List<String> = it.replace(" ", "").split(";")
            if (customButton.size == 9 && !it.startsWith("//")) {
                val keyEvent = if (customButton[0].toIntOrNull() == null || customButton[0].toInt() < 10) {
                    val events = mKeyCharacterMap.getEvents(customButton[0].toLowerCase().toCharArray())
                    if (events.isNullOrEmpty()) return@forEach
                    events[0].keyCode
                } else customButton[0].toInt()
                val visibility = if (customButton[4].toInt() == 1) OscVisibility.ESSENTIAL else OscVisibility.NORMAL

                if (File(Constants.USER_FILE_STORAGE + "/launcher/icons/" + customButton[1]).exists())
                    elements.add(OscImageButton(customButton[0], customButton[1], visibility, R.drawable.inventory, customButton[2].toInt(), customButton[3].toInt(), keyEvent, false, customButton[5].toInt(), customButton[6].toFloat(), if (customButton[7].toInt() == 1) true else false))
                else
                    elements.add(OscHiddenButton(customButton[0], visibility, customButton[2].toInt(), customButton[3].toInt(), customButton[1], keyEvent, customButton[5].toInt(), customButton[6].toFloat(), if (customButton[7].toInt() == 1) true else false, customButton[8].toFloat()))
            }

        }

        val btnRowSpacing = 74
        val btnColumnSpacing = 65

        // Quick buttons: 0 to 9
        for (i in 0..9) {
            val code = KeyEvent.KEYCODE_0 + i
            val column = (i + 1) / 9
            val row = (i + 1) % 9 + 1

            val angle = Math.toRadians(i * 36.0 - 90.0)
            val x = 120.0 * cos(angle) + (512.0)
            val y = 120.0 * (sin(angle) * 1.5) + (384.0)

            quickButtons.add(OscHiddenButton("qp$i", OscVisibility.NULL,
                (x - 30.0).toInt(), (y - 45.0).toInt(), "$i", code))
        }
        qp = OscQuickKeysToggle("qp", OscVisibility.NORMAL,
            512 - 30, 384 - 45, " ", quickButtons)

        topButtons = arrayListOf()

        elements.addAll(quickButtons)
        elements.add(qp)
        elements.addAll(topButtons)
    }

    fun placeElements(target: RelativeLayout) {
        // Quick buttons / QP всегда включены (раньше управлялись через
        // настройку pref_show_qp, которая удалена как рудимент — пользователь
        // хочет, чтобы QP показывались всегда).
        val showQp = true
        for (element in elements) {
            if (!showQp && (element == qp || quickButtons.contains(element)))
                continue

            element.place(target)
            element.loadPrefs(target.context)
        }

        // Keep the two large stick touch fields above the action-button layer. Their
        // ACTION_DOWN hit test passes the centre of real OSC buttons through, while
        // free-space drags stay captured by the stick even when the finger later
        // crosses a button. This fixes the right-look stick getting "lost" nearby.
        joystickLeft.view?.bringToFront()
        joystickRight.view?.bringToFront()

        osk.placeElements(target)

        // Кнопка клавиатуры/F11/F12 — поверх всего, под колесом прокрутки у левого края.
        kbdToggle.placeInto(target)

        target.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob -> relayout(l, t, r, b, ol, ot, or, ob) }

        showBasedOnState()

        // Mouse button is only needed in hybrid mode
        if (GameActivity.mouseMode != MouseMode.Hybrid)
            btnMouse.view?.visibility = View.GONE

    }

    /**
     * SDL запросил текстовый ввод: переводим угловую кнопку в режим
     * клавиатуры. Саму OSK пользователь открывает тапом по этой кнопке.
     */
    fun onTextInputRequested() {
        kbdToggle.setMode(KeyboardToggleMode.KEYBOARD)
    }

    /**
     * Вызывается из SDL при скрытии поля ввода. Клавиатуру, если она была открыта,
     * закрываем, кнопку переводим обратно в режим F12/F11 (но саму кнопку
     * не прячем — так пользователь всегда может тыкнуть F12/F11).
     */
    fun onTextInputDismissed() {
        if (keyboardVisible) {
            osk.hide()
            keyboardVisible = false
            showBasedOnState()
        }
        kbdToggle.setMode(KeyboardToggleMode.POSTPROCESS)
    }

    fun toggleKeyboard() {
        if (keyboardVisible) {
            osk.hide()
            keyboardVisible = false
        } else {
            osk.show()
            keyboardVisible = true
        }
        showBasedOnState()
    }

    fun isKeyboardVisible(): Boolean {
        return keyboardVisible
    }


    /** Короткое «нажал-отпустил» по клавише (для long-press обработчиков). */
    private fun sendKey(keyCode: Int) {
        SDLActivity.onNativeKeyDown(keyCode)
        SDLActivity.onNativeKeyUp(keyCode)
    }

    private fun toggleTopControls() {
        topVisible = !topVisible
        // Note that this is done separate from the showBasedOnState mode
        // Perhaps some refactoring is due
        topButtons.forEach {
            it.view?.visibility = if (topVisible) View.VISIBLE else View.GONE
        }
    }

    /**
     * Displays different controls depending on current state
     * - keyboard visibility
     * - mouse-mode visibility
     * - actual mouse cursor visibility
     */
    fun showBasedOnState() {
        // If keyboard or mouse-mode or both, then hide everything
        if (keyboardVisible || mouseVisible) {
            setVisibility(OscVisibility.NULL.v)
        } else {
            if (SDLActivity.isMouseShown() == 0)
                setVisibility(OscVisibility.ESSENTIAL.v or OscVisibility.NORMAL.v)
            else
                setVisibility(OscVisibility.ESSENTIAL.v)
        }
    }

    fun toggleMouse() {
        mouseVisible = !mouseVisible
        showBasedOnState()
    }

    fun placeConfigurableElements(target: RelativeLayout, listener: View.OnTouchListener) {
        for (element in elements) {
            if (element == qp || quickButtons.contains(element) || !element.configurable)
                continue

            element.placeConfigurable(target, listener)
            element.loadPrefs(target.context)
        }

        target.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob -> relayout(l, t, r, b, ol, ot, or, ob) }
    }

    fun resetElements(ctx: Context) {
        for (element in elements) {
            element.resetPrefs(ctx)
        }
    }

    /**
     * Hide/show stuff based on visibility state
     */
    private fun setVisibility(newState: Int) {
        if (visibilityState == newState)
            return

        for (element in elements) {
            // don't touch elements with NULL visibility as these are managed externally
            if (element.visibility == OscVisibility.NULL)
                continue
            if (newState and element.visibility.v == 0) {
                element.view?.visibility = View.GONE
            } else {
                element.view?.visibility = View.VISIBLE
            }
        }

        visibilityState = newState
    }

    private fun relayout(l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
        // don't do anything if layout didn't change
        if (l == ol && t == ot && r == or && b == ob)
            return
        for (element in elements) {
            element.updateView()
        }
    }

}
