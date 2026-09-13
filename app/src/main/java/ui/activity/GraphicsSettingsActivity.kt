package ui.activity

import android.os.Bundle
import android.preference.PreferenceManager
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.LinearLayout
import android.widget.ScrollView
import android.graphics.Color
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.libopenmw.openmw.R
import file.GraphicsPresets

class GraphicsSettingsActivity : AppCompatActivity() {
    private lateinit var preset: Spinner
    private lateinit var fpsLimit: Spinner
    private lateinit var distance: Spinner
    private lateinit var terrain: Spinner
    private lateinit var water: Spinner
    private lateinit var shadows: Spinner
    private lateinit var shadowMap: Spinner
    private lateinit var shadowDistance: Spinner
    private lateinit var grass: Spinner
    private lateinit var shaders: Spinner
    private lateinit var lighting: Spinner
    private var selectedCategory = 0
    private val lightingValues = listOf("shaders compatibility", "legacy")
    private var ready = false
    private var applyingPreset = false

    private val presetValues = listOf("very_low", "performance", "balanced", "quality", "battery", "custom")
    private val fpsLimitValues = listOf("preset", "30", "60", "0")
    private val distanceValues = listOf("4096", "5120", "6144", "8192", "12288", "16384", "24576", "32768", "40960")
    private val terrainValues = listOf("very_low", "low", "balanced", "medium")
    private val waterValues = listOf("rtt256_no_refraction", "rtt256_refraction", "rtt512_refraction")
    private val shadowValues = listOf("off", "characters", "objects")
    private val shadowMapValues = listOf("512", "1024")
    private val shadowDistanceValues = listOf("1024", "2048", "4096", "6144", "8192")
    private val grassValues = listOf("off", "low", "balanced", "high")
    private val shaderValues = listOf("compatibility", "standard")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_graphics_settings)
        setSupportActionBar(findViewById(R.id.graphics_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.pref_graphics_settings_title)

        preset = findViewById(R.id.gfx_preset); fpsLimit = findViewById(R.id.gfx_fps_limit)
        distance = findViewById(R.id.gfx_distance)
        terrain = findViewById(R.id.gfx_terrain)
        water = findViewById(R.id.gfx_water); shadows = findViewById(R.id.gfx_shadows)
        shadowMap = findViewById(R.id.gfx_shadow_map); shadowDistance = findViewById(R.id.gfx_shadow_distance)
        grass = findViewById(R.id.gfx_grass)
        shaders = findViewById(R.id.gfx_shaders)
        lighting = findViewById(R.id.gfx_lighting)
        bind(lighting, R.array.pref_lighting_method_entries)
        setupCategories(savedInstanceState?.getInt("graphicsCategory", 0) ?: 0)

        bind(preset, R.array.gfx_preset_entries)
        bind(fpsLimit, R.array.gfx_fps_limit_entries)
        bind(distance, R.array.gfx_distance_entries)
        bind(terrain, R.array.gfx_terrain_entries)
        bind(water, R.array.gfx_water_entries)
        bind(shadows, R.array.gfx_shadow_entries)
        bind(shadowMap, R.array.gfx_shadow_map_entries)
        bind(shadowDistance, R.array.gfx_shadow_distance_entries)
        bind(grass, R.array.gfx_grass_entries)
        bind(shaders, R.array.gfx_shader_entries)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        select(lighting, lightingValues, prefs.getString("pref_lighting_method", "shaders compatibility") ?: "shaders compatibility")
        val storedPreset = prefs.getString("pref_graphics_preset", "balanced") ?: "balanced"
        val initialPreset = if (storedPreset == "auto") "balanced" else storedPreset
        select(preset, presetValues, initialPreset)
        select(fpsLimit, fpsLimitValues, prefs.getString("pref_gfx_fps_limit", "preset") ?: "preset")
        if (value(preset, presetValues) == "custom") {
            select(distance, distanceValues, prefs.getString("pref_gfx_view_distance", "12288") ?: "12288")
            select(terrain, terrainValues, prefs.getString("pref_gfx_terrain", "balanced") ?: "balanced")
            select(water, waterValues, prefs.getString("pref_gfx_water", "rtt256_no_refraction") ?: "rtt256_no_refraction")
            select(shadows, shadowValues, prefs.getString("pref_gfx_shadows", "characters") ?: "characters")
            select(shadowMap, shadowMapValues, prefs.getString("pref_gfx_shadow_map", "512") ?: "512")
            select(shadowDistance, shadowDistanceValues, prefs.getString("pref_gfx_shadow_distance", "5120") ?: "5120")
            select(grass, grassValues, prefs.getString("pref_gfx_grass", "balanced") ?: "balanced")
            select(shaders, shaderValues, prefs.getString("pref_gfx_shaders", "compatibility") ?: "compatibility")
        } else loadPreset(value(preset, presetValues))

        ready = true
        preset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!ready || applyingPreset) return
                val idValue = presetValues[position]
                if (idValue != "custom") loadPreset(idValue)
            }
        }
        listOf(distance, terrain, water, shadows, shadowMap, shadowDistance, grass, shaders).forEach { spinner ->
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (ready && !applyingPreset && value(preset, presetValues) != "custom")
                        select(preset, presetValues, "custom")
                }
            }
        }

        findViewById<Button>(R.id.gfx_apply).setOnClickListener {
            prefs.edit()
                .putString("pref_graphics_preset", value(preset, presetValues))
                .putString("pref_gfx_fps_limit", value(fpsLimit, fpsLimitValues))
                .putString("pref_gfx_view_distance", value(distance, distanceValues))
                .putString("pref_gfx_terrain", value(terrain, terrainValues))
                .putString("pref_gfx_preload", "safe")
                .putString("pref_gfx_water", value(water, waterValues))
                .putString("pref_gfx_shadows", value(shadows, shadowValues))
                .putString("pref_gfx_shadow_map", value(shadowMap, shadowMapValues))
                .putString("pref_gfx_shadow_distance", value(shadowDistance, shadowDistanceValues))
                .putString("pref_gfx_grass", value(grass, grassValues))
                .putString("pref_gfx_shaders", value(shaders, shaderValues))
                .putString("pref_lighting_method", value(lighting, lightingValues))
                .apply()
            try { GraphicsPresets.applyToSettings(prefs) } catch (_: Exception) { }
            Toast.makeText(this, R.string.gfx_applied, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupCategories(initial: Int) {
        val navigation = findViewById<LinearLayout>(R.id.gfx_categories)
        val scroll = findViewById<ScrollView>(R.id.gfx_category_scroll)
        val pages = listOf(R.id.gfx_quality_page, R.id.gfx_scene_page, R.id.gfx_water_page,
            R.id.gfx_lighting_page, R.id.gfx_shadow_page).map { findViewById<View>(it) }
        val titles = listOf(R.string.gfx_overall, R.string.gfx_scene_section, R.string.gfx_water,
            R.string.pref_lighting_method, R.string.gfx_shadows)
        val buttons = titles.map { title ->
            Button(this).apply {
                setText(title)
                isAllCaps = false
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
                setPadding(6, 8, 6, 8)
                navigation.addView(this, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        fun selectCategory(index: Int) {
            selectedCategory = index.coerceIn(0, pages.lastIndex)
            pages.forEachIndexed { i, view -> view.visibility = if (i == selectedCategory) View.VISIBLE else View.GONE }
            buttons.forEachIndexed { i, button ->
                button.isSelected = i == selectedCategory
                button.setTextColor(resources.getColor(if (i == selectedCategory) R.color.accentGold else R.color.textPrimary))
                if (i == selectedCategory) button.setBackgroundResource(R.drawable.launcher_field_background)
                else button.setBackgroundColor(Color.TRANSPARENT)
            }
            scroll.post { scroll.scrollTo(0, 0) }
        }
        buttons.forEachIndexed { index, button -> button.setOnClickListener { selectCategory(index) } }
        selectCategory(initial)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("graphicsCategory", selectedCategory)
        super.onSaveInstanceState(outState)
    }

    private fun bind(spinner: Spinner, array: Int) {
        val a = ArrayAdapter.createFromResource(this, array, android.R.layout.simple_spinner_item)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = a
    }
    private fun select(spinner: Spinner, values: List<String>, selected: String) = spinner.setSelection(values.indexOf(selected).coerceAtLeast(0))
    private fun value(spinner: Spinner, values: List<String>) = values[spinner.selectedItemPosition.coerceIn(0, values.lastIndex)]

    private fun loadPreset(id: String) {
        val p = GraphicsPresets.resolve(id) ?: return
        applyingPreset = true
        select(distance, distanceValues, p.viewingDistance.toString())
        val terrainId = when { p.lodFactor <= .40f -> "very_low"; p.lodFactor <= .50f -> "low"; p.lodFactor >= .80f -> "medium"; else -> "balanced" }
        select(terrain, terrainValues, terrainId)
        select(water, waterValues, when {
            p.waterRefraction && p.waterRtt >= 512 -> "rtt512_refraction"
            p.waterRefraction -> "rtt256_refraction"
            else -> "rtt256_no_refraction"
        })
        select(shadows, shadowValues, p.shadowScope)
        select(shadowMap, shadowMapValues, p.shadowResolution.toString())
        select(shadowDistance, shadowDistanceValues, p.shadowDistance.toString())
        select(grass, grassValues, when { !p.grassEnabled -> "off"; p.grassDensity < .7f -> "low"; p.grassDensity >= .95f -> "high"; else -> "balanced" })
        select(shaders, shaderValues, p.shaderProfile)
        applyingPreset = false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }
}
