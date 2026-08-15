/* ArenaMP Android mobile graphics configuration. */
package file

import android.content.SharedPreferences
import constants.Constants

object GraphicsPresets {
    data class Preset(
        val osgThreading: String,
        val pagerThreads: Int,
        val dbThreads: Int,
        val compileThreads: Int,
        val maxPagedLOD: Int,
        val shaderCache: Boolean,
        val viewingDistance: Int,
        val lodFactor: Float,
        val vertexLodMod: Int,
        val compositeMapLevel: Int,
        val compositeMapResolution: Int,
        val objectPaging: Boolean,
        val distantTerrain: Boolean,
        val preloadDistance: Int,
        val preloadThreads: Int,
        val targetFramerate: Int,
        val frameLimit: Float,
        val waterMode: String,
        val waterRtt: Int,
        val waterRefraction: Boolean,
        val waterReflectionDetail: Int,
        val shadowScope: String,
        val shadowResolution: Int,
        val shadowDistance: Int,
        val grassEnabled: Boolean,
        val grassDensity: Float,
        val grassDistance: Int,
        val shaderProfile: String,
        val landOptimization: String,
        val asyncNumThreads: Int = 1
    )

    val PRESETS: Map<String, Preset> = mapOf(
        // Mobile profiles keep paging/preload workers deliberately low. A quick camera turn
        // should pull from warmed caches rather than start several competing loaders at once.
        "very_low" to Preset(
            "CullDrawThreadPerContext", 1, 1, 1, 4, true,
            4096, .40f, -2, -3, 1024, true, true,
            1000, 1, 60, 45.03f, "simple", 256, false, 1,
            "off", 512, 4096, true, .80f, 7100, "compatibility", "performance"
        ),
        "performance" to Preset(
            "CullDrawThreadPerContext", 1, 1, 1, 5, true,
            8192, .50f, -2, -3, 1024, true, true,
            1000, 1, 60, 45.0f, "simple", 256, false, 1,
            "off", 512, 4096, true, .65f, 5000, "compatibility", "performance"
        ),
        "balanced" to Preset(
            "CullDrawThreadPerContext", 1, 1, 1, 6, true,
            12288, .65f, -1, -2, 1024, true, true,
            1000, 1, 60, 60.0f, "simple", 256, false, 2,
            "characters", 1024, 6144, true, .80f, 6000, "compatibility", "performance"
        ),
        "quality" to Preset(
            "CullDrawThreadPerContext", 1, 1, 1, 8, true,
            24576, .80f, -1, -2, 2048, true, true,
            1000, 1, 60, 60.0f, "simple", 256, false, 2,
            "objects", 1024, 8192, true, 1.0f, 7100, "standard", "balance"
        ),
        "battery" to Preset(
            "SingleThreaded", 1, 1, 1, 3, true,
            4096, .40f, -2, -3, 1024, true, true,
            1000, 1, 30, 30.0f, "simple", 256, false, 1,
            "off", 512, 3072, false, .50f, 3500, "compatibility", "balance"
        )
    )

    fun resolve(presetId: String?): Preset? {
        if (presetId.isNullOrBlank() || presetId == "auto") return PRESETS["balanced"]
        return PRESETS[presetId]
    }

    fun resolve(prefs: SharedPreferences): Preset {
        val id = prefs.getString("pref_graphics_preset", "balanced") ?: "balanced"
        if (id != "custom") return resolve(id) ?: PRESETS.getValue("balanced")
        val base = PRESETS.getValue("balanced")
        val terrain = prefs.getString("pref_gfx_terrain", "balanced") ?: "balanced"
        val terrainValues = when (terrain) {
            "very_low" -> floatArrayOf(.40f, -2f, -3f, 1024f)
            "low" -> floatArrayOf(.50f, -2f, -3f, 1024f)
            "medium" -> floatArrayOf(.80f, -1f, -2f, 2048f)
            else -> floatArrayOf(.65f, -1f, -2f, 1024f)
        }
        // ArenaMP Android: keep preload distance conservative on every profile.
        // Large preload radii caused visible I/O / upload bursts during abrupt camera turns.
        val preloadDistance = 1000
        val preloadThreads = 1
        // V13.7.4: Android exposes only the stable simple water path.
        // Ignore stale PBR water preferences from older launcher versions.
        val waterMode = "simple"
        val waterRtt = 256
        val waterRefraction = false
        val waterReflection = 2
        val grass = prefs.getString("pref_gfx_grass", "balanced") ?: "balanced"
        val grassEnabled = grass != "off"
        val grassDensity = when (grass) { "low" -> .55f; "high" -> 1.0f; else -> .80f }
        val grassDistance = when (grass) { "low" -> 4000; "high" -> 7100; else -> 6000 }
        return base.copy(
            viewingDistance = (prefs.getString("pref_gfx_view_distance", "12288") ?: "12288").toIntOrNull()?.coerceIn(4096, 40960) ?: 12288,
            lodFactor = terrainValues[0], vertexLodMod = terrainValues[1].toInt(),
            compositeMapLevel = terrainValues[2].toInt(), compositeMapResolution = terrainValues[3].toInt(),
            preloadDistance = preloadDistance, preloadThreads = preloadThreads,
            waterMode = waterMode, waterRtt = waterRtt, waterRefraction = waterRefraction, waterReflectionDetail = waterReflection,
            shadowScope = prefs.getString("pref_gfx_shadows", "characters") ?: "characters",
            shadowResolution = ((prefs.getString("pref_gfx_shadow_map", "512") ?: "512").toIntOrNull() ?: 512).coerceIn(512, 1024),
            shadowDistance = ((prefs.getString("pref_gfx_shadow_distance", "5120") ?: "5120").toIntOrNull() ?: 5120).coerceIn(1024, 8192),
            grassEnabled = grassEnabled, grassDensity = grassDensity, grassDistance = grassDistance,
            shaderProfile = prefs.getString("pref_gfx_shaders", "compatibility") ?: "compatibility",
            landOptimization = when (terrain) { "medium" -> "balance"; else -> "performance" }
        )
    }

    fun applyToSettings(prefs: SharedPreferences) {
        val p = resolve(prefs)
        val cfg = Constants.USER_CONFIG + "/settings.cfg"
        fun w(section: String, key: String, value: Any) = Writer.write(cfg, section, key, value.toString())
        fun b(value: Boolean) = if (value) "true" else "false"

        val terrainTier = when {
            p.lodFactor <= .40f -> 0
            p.lodFactor <= .50f -> 1
            p.lodFactor <= .65f -> 2
            else -> 3
        }
        val maxCompositeGeometry = floatArrayOf(4f, 4f, 6f, 8f)[terrainTier]
        val pagingMerge = floatArrayOf(100000f, 75000f, 50000f, 30000f)[terrainTier]
        val pagingMinSize = floatArrayOf(1f, .85f, .65f, .50f)[terrainTier]
        val cullingPixels = intArrayOf(12, 11, 10, 8)[terrainTier]
        val occWidth = intArrayOf(384, 384, 512, 512)[terrainTier]
        val occHeight = intArrayOf(192, 192, 256, 256)[terrainTier]
        val occTerrainRadius = intArrayOf(4, 4, 6, 8)[terrainTier]
        val occMinRadius = floatArrayOf(650f, 600f, 550f, 500f)[terrainTier]
        val occMaxRadius = floatArrayOf(3200f, 3600f, 4000f, 4400f)[terrainTier]
        val occShrink = floatArrayOf(.70f, .72f, .74f, .76f)[terrainTier]
        val occMesh = intArrayOf(8, 8, 10, 10)[terrainTier]
        val occMaxMesh = intArrayOf(24, 24, 28, 28)[terrainTier]
        val occInside = floatArrayOf(.96f, .95f, .94f, .93f)[terrainTier]
        val occMaxDistance = floatArrayOf(3072f, 3584f, 4096f, 5120f)[terrainTier]
        val cellCacheMax = intArrayOf(24, 32, 48, 64)[terrainTier]

        w("Camera", "viewing distance", p.viewingDistance.coerceIn(4096, 40960))
        w("Camera", "optimization land", p.landOptimization)
        w("Camera", "small feature culling", "true")
        w("Camera", "small feature culling pixel size", cullingPixels)
        w("Camera", "occlusion culling", "true")
        w("Camera", "occlusion culling terrain", "true")
        w("Camera", "occlusion culling statics", "true")
        w("Camera", "occlusion buffer width", occWidth)
        w("Camera", "occlusion buffer height", occHeight)
        w("Camera", "occlusion terrain radius", occTerrainRadius)
        w("Camera", "occlusion occluder min radius", occMinRadius)
        w("Camera", "occlusion occluder max radius", occMaxRadius)
        w("Camera", "occlusion occluder shrink factor", occShrink)
        w("Camera", "occlusion occluder mesh resolution", occMesh)
        w("Camera", "occlusion occluder max mesh resolution", occMaxMesh)
        w("Camera", "occlusion occluder inside threshold", occInside)
        w("Camera", "occlusion occluder max distance", minOf(p.viewingDistance.toFloat(), occMaxDistance))
        w("Terrain", "distant terrain", b(p.distantTerrain))
        w("Terrain", "lod factor", p.lodFactor)
        w("Terrain", "vertex lod mod", p.vertexLodMod)
        w("Terrain", "composite map level", p.compositeMapLevel)
        w("Terrain", "composite map resolution", p.compositeMapResolution)
        w("Terrain", "max composite geometry size", maxCompositeGeometry)
        w("Terrain", "object paging", b(p.objectPaging))
        w("Terrain", "object paging active grid", "true")
        w("Terrain", "object paging merge factor", pagingMerge)
        w("Terrain", "object paging min size", pagingMinSize)
        w("Terrain", "object paging min size merge factor", ".039")
        w("Terrain", "object paging min size cost multiplier", "1")

        w("Cells", "preload enabled", "true")
        w("Cells", "preload num threads", p.preloadThreads)
        w("Cells", "preload exterior grid", "true")
        w("Cells", "preload fast travel", "false")
        w("Cells", "preload doors", "false")
        w("Cells", "preload distance", p.preloadDistance)
        w("Cells", "preload instances", "true")
        w("Cells", "preload cell cache min", "16")
        w("Cells", "preload cell cache max", cellCacheMax)
        w("Cells", "preload cell expiry delay", "10")
        w("Cells", "prediction time", "2")
        w("Cells", "cache expiry delay", "10")
        w("Cells", "target framerate", p.targetFramerate)
        w("Physics", "async num threads", p.asyncNumThreads)
        w("Video", "antialiasing", "0")
        w("Video", "framerate limit", p.frameLimit)

        // Stable Android shader path. Keep ArenaMP's fullscreen native post chain
        // completely off on NG-GL4ES; C++ enforces the same policy as a fail-safe.
        w("Shaders", "force shaders", "true")
        w("Shaders", "force per pixel lighting", "false")
        w("Shaders", "lighting method", "shaders compatibility")
        w("Shaders", "enhanced pbr lighting", "false")
        val standard = p.shaderProfile == "standard"
        w("Shaders", "material quality", if (standard) "balanced" else "none")
        w("Shaders", "auto use object normal maps", b(standard))
        w("Shaders", "auto use object specular maps", b(standard))
        w("Shaders", "auto use terrain normal maps", b(standard))
        w("Shaders", "auto use terrain specular maps", b(standard))
        w("Shaders", "antialias alpha test", "false")
        w("Shaders", "hdr lighting", "false")
        w("Shaders", "bloom enabled", "false")
        w("Shaders", "native ssr enabled", "false")
        w("Shaders", "smaa enabled", "false")
        w("Shaders", "atmospheric fog enabled", "false")
        w("Shaders", "god rays enabled", "false")
        w("Shaders", "sharpening enabled", "false")
        w("Shaders", "dithering enabled", "false")

        // V13.7.4: simple shader water is authoritative on Android. This also
        // overwrites stale "new"/PBR values left by older launcher builds.
        w("Water", "shader mode", p.waterMode)
        w("Water", "shader", b(p.waterMode != "off"))
        w("Water", "rtt size", p.waterRtt)
        w("Water", "refraction", b(p.waterRefraction))
        w("Water", "shader water ripples", "true")
        w("Water", "reflection detail", p.waterReflectionDetail)

        val shadowOn = p.shadowScope != "off"
        w("Shadows", "enable shadows", b(shadowOn))
        w("Shadows", "player shadows", b(shadowOn))
        w("Shadows", "actor shadows", b(p.shadowScope == "characters" || p.shadowScope == "objects"))
        w("Shadows", "object shadows", b(p.shadowScope == "objects"))
        w("Shadows", "terrain shadows", "false")
        w("Shadows", "enable indoor shadows", "false")
        w("Shadows", "shadow map resolution", p.shadowResolution.coerceAtMost(1024))
        w("Shadows", "number of shadow maps", if (shadowOn) "2" else "1")
        w("Shadows", "maximum shadow map distance", p.shadowDistance.coerceIn(0, 8192))
        w("Shadows", "shadow fade start", "0.82")
        w("Shadows", "allow shadow map overlap", "false")
        w("Shadows", "enhanced filtering", "false")

        w("Groundcover", "enabled", b(p.grassEnabled))
        w("Groundcover", "density", p.grassDensity)
        w("Groundcover", "rendering distance", p.grassDistance.coerceAtMost(8192))
    }
}
