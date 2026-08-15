package ui.activity

import android.os.Bundle
import android.preference.PreferenceManager
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.libopenmw.openmw.R
import file.GraphicsPresets

class GraphicsSettingsActivity : AppCompatActivity() {
    private lateinit var preset: Spinner
    private lateinit var distance: Spinner
    private lateinit var terrain: Spinner
    private lateinit var water: Spinner
    private lateinit var shadows: Spinner
    private lateinit var shadowMap: Spinner
    private lateinit var shadowDistance: Spinner
    private lateinit var grass: Spinner
    private lateinit var shaders: Spinner
    private var ready = false
    private var applyingPreset = false

    private val presetValues = listOf("very_low", "performance", "balanced", "quality", "battery", "custom")
    private val distanceValues = listOf("4096", "5120", "6144", "8192", "12288", "16384", "24576", "32768", "40960")
    private val terrainValues = listOf("very_low", "low", "balanced", "medium")
    private val waterValues = listOf("simple")
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

        preset = findViewById(R.id.gfx_preset); distance = findViewById(R.id.gfx_distance)
        terrain = findViewById(R.id.gfx_terrain)
        water = findViewById(R.id.gfx_water); shadows = findViewById(R.id.gfx_shadows)
        shadowMap = findViewById(R.id.gfx_shadow_map); shadowDistance = findViewById(R.id.gfx_shadow_distance)
        grass = findViewById(R.id.gfx_grass)
        shaders = findViewById(R.id.gfx_shaders)

        bind(preset, R.array.gfx_preset_entries)
        bind(distance, R.array.gfx_distance_entries)
        bind(terrain, R.array.gfx_terrain_entries)
        bind(water, R.array.gfx_water_entries)
        bind(shadows, R.array.gfx_shadow_entries)
        bind(shadowMap, R.array.gfx_shadow_map_entries)
        bind(shadowDistance, R.array.gfx_shadow_distance_entries)
        bind(grass, R.array.gfx_grass_entries)
        bind(shaders, R.array.gfx_shader_entries)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val storedPreset = prefs.getString("pref_graphics_preset", "balanced") ?: "balanced"
        val initialPreset = if (storedPreset == "auto") "balanced" else storedPreset
        select(preset, presetValues, initialPreset)
        if (value(preset, presetValues) == "custom") {
            select(distance, distanceValues, prefs.getString("pref_gfx_view_distance", "12288") ?: "12288")
            select(terrain, terrainValues, prefs.getString("pref_gfx_terrain", "balanced") ?: "balanced")
            select(water, waterValues, "simple")
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
                .putString("pref_gfx_view_distance", value(distance, distanceValues))
                .putString("pref_gfx_terrain", value(terrain, terrainValues))
                .putString("pref_gfx_preload", "safe")
                .putString("pref_gfx_water", "simple")
                .putString("pref_gfx_shadows", value(shadows, shadowValues))
                .putString("pref_gfx_shadow_map", value(shadowMap, shadowMapValues))
                .putString("pref_gfx_shadow_distance", value(shadowDistance, shadowDistanceValues))
                .putString("pref_gfx_grass", value(grass, grassValues))
                .putString("pref_gfx_shaders", value(shaders, shaderValues))
                .apply()
            try { GraphicsPresets.applyToSettings(prefs) } catch (_: Exception) { }
            Toast.makeText(this, R.string.gfx_applied, Toast.LENGTH_SHORT).show()
            finish()
        }
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
        select(water, waterValues, "simple")
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
