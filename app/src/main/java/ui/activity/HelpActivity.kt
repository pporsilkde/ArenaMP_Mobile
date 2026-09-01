package ui.activity

import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.libopenmw.openmw.R

class HelpActivity : AppCompatActivity() {
    private data class HelpItem(val iconRes: Int, val titleRes: Int, val descriptionRes: Int)
    private data class HelpSection(val titleRes: Int, val items: List<HelpItem>, val noteRes: Int = 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        setSupportActionBar(findViewById(R.id.help_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.help_title)

        val content: LinearLayout = findViewById(R.id.help_content)
        addIntro(content)
        sections().forEach { addSection(content, it) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun addIntro(parent: LinearLayout) {
        val panel = panel()
        val title = TextView(this).apply {
            setText(R.string.help_welcome_title)
            textSize = 21f
            setTextColor(resources.getColor(R.color.accentGold))
        }
        val body = TextView(this).apply {
            setText(R.string.help_welcome_desc)
            textSize = 15f
            setTextColor(resources.getColor(R.color.textSecondary))
            setPadding(0, dp(7), 0, 0)
        }
        panel.addView(title)
        panel.addView(body)
        parent.addView(panel, sectionLayoutParams(first = true))
    }

    private fun addSection(parent: LinearLayout, section: HelpSection) {
        val panel = panel()
        val header = TextView(this).apply {
            setText(section.titleRes)
            textSize = 16f
            setTextColor(resources.getColor(R.color.accentGoldMuted))
            setPadding(0, 0, 0, dp(5))
        }
        panel.addView(header)

        section.items.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val icon = ImageView(this).apply {
                setImageResource(item.iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                alpha = 0.94f
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(14) })

            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.addView(TextView(this).apply {
                setText(item.titleRes)
                textSize = 18f
                setTextColor(resources.getColor(R.color.textPrimary))
            })
            text.addView(TextView(this).apply {
                setText(item.descriptionRes)
                textSize = 14f
                setTextColor(resources.getColor(R.color.textSecondary))
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            panel.addView(row)

            if (index != section.items.lastIndex) {
                panel.addView(View(this).apply { setBackgroundColor(resources.getColor(R.color.bgDivider)) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            }
        }

        if (section.noteRes != 0) {
            panel.addView(TextView(this).apply {
                setText(section.noteRes)
                textSize = 13f
                setTextColor(resources.getColor(R.color.accentGoldMuted))
                setPadding(0, dp(8), 0, 0)
            })
        }
        parent.addView(panel, sectionLayoutParams(first = false))
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.launcher_panel_background)
    }

    private fun sectionLayoutParams(first: Boolean): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (!first) topMargin = dp(12)
        }

    private fun sections(): List<HelpSection> = listOf(
        HelpSection(R.string.help_section_start, listOf(
            HelpItem(R.drawable.ic_file, R.string.help_files_title, R.string.help_files_desc),
            HelpItem(R.drawable.inventory, R.string.help_mods_title, R.string.help_mods_desc),
            HelpItem(R.drawable.postprocessing, R.string.help_graphics_title, R.string.help_graphics_desc),
            HelpItem(R.drawable.stats, R.string.help_fps_title, R.string.help_fps_desc)
        )),
        HelpSection(R.string.help_section_controls, listOf(
            HelpItem(R.drawable.run, R.string.controls_help_move_title, R.string.controls_help_move_desc),
            HelpItem(R.drawable.mouse, R.string.controls_help_look_title, R.string.controls_help_look_desc),
            HelpItem(R.drawable.attack, R.string.controls_help_attack_title, R.string.controls_help_attack_desc),
            HelpItem(R.drawable.use, R.string.controls_help_use_title, R.string.controls_help_use_desc),
            HelpItem(R.drawable.jump, R.string.controls_help_jump_title, R.string.controls_help_jump_desc),
            HelpItem(R.drawable.sneak, R.string.controls_help_sneak_title, R.string.controls_help_sneak_desc),
            HelpItem(R.drawable.pause, R.string.controls_help_pause_title, R.string.controls_help_pause_desc),
            HelpItem(R.drawable.inventory, R.string.controls_help_inventory_title, R.string.controls_help_inventory_desc),
            HelpItem(R.drawable.save, R.string.controls_help_chat_title, R.string.controls_help_chat_desc),
            HelpItem(R.drawable.toggle_magic, R.string.controls_help_magic_title, R.string.controls_help_magic_desc),
            HelpItem(R.drawable.toggle_weapon, R.string.controls_help_weapon_title, R.string.controls_help_weapon_desc),
            HelpItem(R.drawable.journal, R.string.controls_help_journal_title, R.string.controls_help_journal_desc),
            HelpItem(R.drawable.scroll_wheel, R.string.controls_help_scroll_title, R.string.controls_help_scroll_desc),
            HelpItem(R.drawable.postprocessing, R.string.controls_help_corner_title, R.string.controls_help_corner_desc),
            HelpItem(R.drawable.stats, R.string.controls_help_quick_title, R.string.controls_help_quick_desc)
        ), R.string.help_custom_controls_note),
        HelpSection(R.string.help_section_gestures, listOf(
            HelpItem(R.drawable.use, R.string.help_hold_e_title, R.string.help_hold_e_desc),
            HelpItem(R.drawable.jump, R.string.help_autorun_title, R.string.help_autorun_desc),
            HelpItem(R.drawable.journal, R.string.help_console_title, R.string.help_console_desc),
            HelpItem(R.drawable.postprocessing, R.string.help_hudshot_title, R.string.help_hudshot_desc),
            HelpItem(R.drawable.keyboard, R.string.help_keyboard_title, R.string.help_keyboard_desc),
            HelpItem(R.drawable.mouse, R.string.help_mouse_title, R.string.help_mouse_desc)
        )),
        HelpSection(R.string.help_section_graphics, listOf(
            HelpItem(R.drawable.stats, R.string.help_fps_modes_title, R.string.help_fps_modes_desc),
            HelpItem(R.drawable.postprocessing, R.string.help_apply_title, R.string.help_apply_desc),
            HelpItem(R.drawable.ic_grass, R.string.help_performance_title, R.string.help_performance_desc)
        )),
        HelpSection(R.string.help_section_data, listOf(
            HelpItem(R.drawable.inventory, R.string.help_load_order_title, R.string.help_load_order_desc),
            HelpItem(R.drawable.save, R.string.help_saves_title, R.string.help_saves_desc),
            HelpItem(R.drawable.ic_file, R.string.help_storage_title, R.string.help_storage_desc)
        )),
        HelpSection(R.string.help_section_multiplayer, listOf(
            HelpItem(R.drawable.ic_globe, R.string.help_mp_connection_title, R.string.help_mp_connection_desc),
            HelpItem(R.drawable.save, R.string.help_mp_chat_title, R.string.help_mp_chat_desc),
            HelpItem(R.drawable.scroll_wheel, R.string.help_mp_tab_title, R.string.help_mp_tab_desc),
            HelpItem(R.drawable.ic_start_button, R.string.help_mp_server_title, R.string.help_mp_server_desc),
            HelpItem(R.drawable.ic_update, R.string.help_mp_compat_title, R.string.help_mp_compat_desc)
        )),
        HelpSection(R.string.help_section_troubleshooting, listOf(
            HelpItem(R.drawable.pause, R.string.help_controls_reset_title, R.string.help_controls_reset_desc),
            HelpItem(R.drawable.stats, R.string.help_lowfps_title, R.string.help_lowfps_desc),
            HelpItem(R.drawable.postprocessing, R.string.help_render_title, R.string.help_render_desc),
            HelpItem(R.drawable.ic_update, R.string.help_restart_title, R.string.help_restart_desc)
        ), R.string.help_final_note)
    )
}
