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

package ui.activity

import com.libopenmw.openmw.R

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView

import ui.controls.Osc
import ui.controls.OscElement
import ui.controls.VIRTUAL_SCREEN_HEIGHT
import ui.controls.VIRTUAL_SCREEN_WIDTH
import utils.Utils.hideAndroidControls

class ConfigureCallback(activity: Activity) : View.OnTouchListener {

    var currentView: View? = null
    private var layout: RelativeLayout = activity.findViewById(R.id.controlsContainer)
    private var origX: Float = 0.0f
    private var origY: Float = 0.0f
    private var startX: Float = 0.0f
    private var startY: Float = 0.0f

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentView?.setBackgroundColor(Color.TRANSPARENT)
                currentView = v
                v.setBackgroundColor(Color.RED)
                origX = v.x
                origY = v.y
                startX = event.rawX
                startY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> if (currentView != null) {
                val view = currentView!!
                val x = ((event.rawX - startX) + origX).toInt()
                val y = ((event.rawY - startY) + origY).toInt()

                val el = view.tag as OscElement
                el.changePosition(x * VIRTUAL_SCREEN_WIDTH / layout.width, y * VIRTUAL_SCREEN_HEIGHT / layout.height)
                el.updateView()
            }
        }

        return true
    }

}

class ConfigureControls : Activity() {

    private var callback: ConfigureCallback? = null
    private var osc = Osc()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.configure_controls)

        val cb = ConfigureCallback(this)
        callback = cb

        val container: RelativeLayout = findViewById(R.id.controlsContainer)
        osc.placeConfigurableElements(container, cb)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideAndroidControls(this)
        }
    }

    private fun changeOpacity(delta: Float) {
        val view = callback?.currentView ?: return
        val el =  view.tag as OscElement
        el.changeOpacity(delta)
        el.updateView()
    }

    private fun changeSize(delta: Int) {
        val view = callback?.currentView ?: return
        val el =  view.tag as OscElement
        el.changeSize(delta)
        el.updateView()
    }

    fun clickOpacityPlus(v: View) {
        changeOpacity(0.1f)
    }

    fun clickOpacityMinus(v: View) {
        changeOpacity(-0.1f)
    }

    fun clickSizePlus(v: View) {
        changeSize(5)
    }

    fun clickSizeMinus(v: View) {
        changeSize(-5)
    }

    fun clickResetControls(v: View) {
        osc.resetElements(applicationContext)
    }


    private data class ControlHelpItem(
        val iconRes: Int,
        val titleRes: Int,
        val descriptionRes: Int
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    fun clickControlsHelp(v: View) {
        val items = listOf(
            ControlHelpItem(R.drawable.run, R.string.controls_help_move_title, R.string.controls_help_move_desc),
            ControlHelpItem(R.drawable.mouse, R.string.controls_help_look_title, R.string.controls_help_look_desc),
            ControlHelpItem(R.drawable.attack, R.string.controls_help_attack_title, R.string.controls_help_attack_desc),
            ControlHelpItem(R.drawable.use, R.string.controls_help_use_title, R.string.controls_help_use_desc),
            ControlHelpItem(R.drawable.jump, R.string.controls_help_jump_title, R.string.controls_help_jump_desc),
            ControlHelpItem(R.drawable.sneak, R.string.controls_help_sneak_title, R.string.controls_help_sneak_desc),
            ControlHelpItem(R.drawable.pause, R.string.controls_help_pause_title, R.string.controls_help_pause_desc),
            ControlHelpItem(R.drawable.inventory, R.string.controls_help_inventory_title, R.string.controls_help_inventory_desc),
            ControlHelpItem(R.drawable.save, R.string.controls_help_chat_title, R.string.controls_help_chat_desc),
            ControlHelpItem(R.drawable.toggle_magic, R.string.controls_help_magic_title, R.string.controls_help_magic_desc),
            ControlHelpItem(R.drawable.toggle_weapon, R.string.controls_help_weapon_title, R.string.controls_help_weapon_desc),
            ControlHelpItem(R.drawable.journal, R.string.controls_help_journal_title, R.string.controls_help_journal_desc),
            ControlHelpItem(R.drawable.scroll_wheel, R.string.controls_help_scroll_title, R.string.controls_help_scroll_desc),
            ControlHelpItem(R.drawable.postprocessing, R.string.controls_help_corner_title, R.string.controls_help_corner_desc),
            ControlHelpItem(R.drawable.stats, R.string.controls_help_quick_title, R.string.controls_help_quick_desc)
        )

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
        }

        items.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }

            val icon = ImageView(this).apply {
                setImageResource(item.iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                alpha = 0.92f
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)).apply { rightMargin = dp(14) })

            val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(this).apply {
                setText(item.titleRes)
                textSize = 18f
                setTextColor(resources.getColor(R.color.accentGold))
            }
            val description = TextView(this).apply {
                setText(item.descriptionRes)
                textSize = 15f
                setTextColor(resources.getColor(R.color.textSecondary))
                setPadding(0, dp(3), 0, 0)
            }
            textColumn.addView(title)
            textColumn.addView(description)
            row.addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row)

            if (index != items.lastIndex) {
                list.addView(View(this).apply { setBackgroundColor(resources.getColor(R.color.bgDivider)) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            }
        }

        val note = TextView(this).apply {
            setText(R.string.controls_help_note)
            textSize = 14f
            setTextColor(resources.getColor(R.color.textSecondary))
            setPadding(0, dp(14), 0, dp(4))
        }
        list.addView(note)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.controls_help_title)
            .setView(scroll)
            .setPositiveButton(R.string.btn_back, null)
            .create()
        dialog.setOnShowListener {
            val dm = resources.displayMetrics
            dialog.window?.setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.86f).toInt())
            dialog.window?.decorView?.systemUiVisibility = window.decorView.systemUiVisibility
        }
        dialog.show()
    }

    fun clickBack(v: View) {
        finish()
    }

}
