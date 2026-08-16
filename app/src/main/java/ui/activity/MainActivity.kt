/*
    Copyright (C) 2015, 2016 sandstranger
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

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.ProgressDialog
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.system.ErrnoException
import android.system.Os
import android.util.DisplayMetrics
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.Toast
import com.bugsnag.android.Bugsnag

import com.libopenmw.openmw.BuildConfig
import com.libopenmw.openmw.R
import constants.Constants
import file.GameInstaller
import file.GraphicsPresets
import file.BuildManifest
import file.UpdateDownloader
import android.widget.ImageButton

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader

import file.utils.CopyFilesFromAssets
import mods.ModType
import mods.ModsCollection
import mods.ModsDatabaseOpenHelper
import ui.fragments.FragmentSettings
import permission.PermissionHelper
import utils.MyApp
import utils.Utils.hideAndroidControls
import java.util.*

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MyApp.app.defaultScaling = determineScaling()

        PermissionHelper.getWriteExternalStoragePermission(this@MainActivity)
        setContentView(R.layout.main)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        fragmentManager.beginTransaction()
            .replace(R.id.content_frame, FragmentSettings()).commit()

        setSupportActionBar(findViewById(R.id.main_toolbar))

        val fab = findViewById<FloatingActionButton>(R.id.fab)
        fab.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate()
                        .scaleX(0.92f)
                        .scaleY(0.92f)
                        .translationY(6f)
                        .setDuration(80)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .setDuration(120)
                        .start()
                }
            }
            false
        }
        fab.setOnClickListener { checkStartGame() }

        // Update button (download arrow) - right next to the toolbar overflow 3-dots
        findViewById<ImageButton?>(R.id.btn_update)?.setOnClickListener {
            val url = prefs.getString(
                UpdateDownloader.PREF_UPDATE_URL,
                UpdateDownloader.DEFAULT_UPDATE_URL
            )!!
            val gameFiles = prefs.getString("game_files", "") ?: ""
            UpdateDownloader.startUpdate(this, gameFiles, url)
        }

        // Globe icon -> opens morrowind.site
        findViewById<ImageButton?>(R.id.btn_globe)?.setOnClickListener {
            openUrl(UpdateDownloader.MORROWIND_SITE)
        }

        if (prefs.getString("bugsnag_consent", "")!! == "") {
            askBugsnagConsent()
        }
    }

    /**
     * Set new user consent and maybe restart the app
     * @param consent New value of bugsnag consent
     */
    @SuppressLint("ApplySharedPref")
    private fun setBugsnagConsent(consent: String) {
        val currentConsent = prefs.getString("bugsnag_consent", "")!!
        if (currentConsent == consent)
            return

        // We only need to force a restart if the user revokes their consent
        // If user grants consent, crashes won't be reported for 1 game session, but that's alright
        val needRestart = currentConsent == "true" && consent == "false"

        with (prefs.edit()) {
            putString("bugsnag_consent", consent)
            commit()
        }

        if (needRestart) {
            AlertDialog.Builder(this)
                .setOnDismissListener { System.exit(0) }
                .setTitle(R.string.bugsnag_consent_restart_title)
                .setMessage(R.string.bugsnag_consent_restart_message)
                .setPositiveButton(android.R.string.ok) { _, _ -> System.exit(0) }
                .show()
        }
    }

    /**
     * Opens the url in a web browser and gracefully handles the failure
     * @param url Url to open
     */
    fun openUrl(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(browserIntent)
        } catch (e: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setTitle(R.string.no_browser_title)
                .setMessage(getString(R.string.no_browser_message, url))
                .setPositiveButton(android.R.string.ok) { _, _ -> }
                .show()
        }
    }

    /**
     * Asks the user if they want to automatically report crashes
     */
    private fun askBugsnagConsent() {
        // Do nothing for builds without api-key
        if (!MyApp.haveBugsnagApiKey)
            return

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.bugsnag_consent_title)
            .setMessage(R.string.bugsnag_consent_message)
            .setNeutralButton(R.string.bugsnag_policy) { _, _ -> /* set up below */ }
            .setNegativeButton(R.string.bugsnag_no) { _, _ -> setBugsnagConsent("false") }
            .setPositiveButton(R.string.bugsnag_yes) { _, _ -> setBugsnagConsent("true") }
            .create()

        dialog.show()

        // don't close the dialog when the privacy-policy button is clicked
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            openUrl("https://omw.xyz.is/privacy-policy.html")
        }
    }

    /**
     * Checks that the game is properly installed and if so, starts the game
     * - the game files must be selected
     * - there must be at least 1 activated mod (user can ignore this warning)
     */
    private fun checkStartGame() {
        // First, check that there are game files present
        val inst = GameInstaller(prefs.getString("game_files", "")!!)
        if (!inst.check()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.no_data_files_title)
                .setMessage(R.string.no_data_files_message)
                .setNeutralButton(R.string.dialog_howto) { _, _ ->
                    openUrl("https://omw.xyz.is/game.html")
                }
                .setPositiveButton(android.R.string.ok) { _: DialogInterface, _: Int -> }
                .show()
            return
        }

        // build.ini is authoritative when present, just like desktop ArenaMP.
        BuildManifest.applyToDatabase(this)

        // Second, check if user has at least one mod enabled
        val plugins = ModsCollection(ModType.Plugin, inst.findDataFiles(),
            ModsDatabaseOpenHelper.getInstance(this))
        if (plugins.mods.count { it.enabled } == 0) {
            // No mods enabled, show a warning
            AlertDialog.Builder(this)
                .setTitle(R.string.no_content_files_title)
                .setMessage(R.string.no_content_files_message)
                .setNeutralButton(R.string.dialog_howto) { _, _ ->
                    openUrl("https://omw.xyz.is/mods.html")
                }
                .setNegativeButton(R.string.no_content_files_dismiss) { _, _ -> startGame() }
                .setPositiveButton(R.string.configure_mods) { _, _ ->
                    this.startActivity(Intent(this, ModsActivity::class.java))
                }
                .show()

            return
        }

        // If everything's alright, start the game
        startGame()
    }

    private fun deleteRecursive(fileOrDirectory: File) {
        if (fileOrDirectory.isDirectory)
            for (child in fileOrDirectory.listFiles())
                deleteRecursive(child)

        fileOrDirectory.delete()
    }

    private fun logConfig() {

    }

    private fun runGame() {
        logConfig()
        val intent = Intent(this@MainActivity,
            GameActivity::class.java)
        finish()

        this@MainActivity.startActivityForResult(intent, 1)
    }


    /**
     * Set up fixed screen resolution
     * This doesn't do anything unless the user chose to override screen resolution
     */
    private fun obtainFixedScreenResolution() {
        // Split resolution e.g 640x480 to width/height
        val customResolution = prefs.getString("pref_customResolution", "")
        val sep = customResolution!!.indexOf("x")
        if (sep > 0) {
            try {
                val x = Integer.parseInt(customResolution.substring(0, sep))
                val y = Integer.parseInt(customResolution.substring(sep + 1))

                resolutionX = x
                resolutionY = y
            } catch (e: NumberFormatException) {
                // user entered resolution wrong, just ignore it
            }
        }
    }

    /**
     * Generates openmw.cfg using values from openmw.base.cfg combined with mod manager settings
     */
    private fun generateOpenmwCfg() {
        // contents of openmw.base.cfg
        val base: String
        // contents of openmw.fallback.cfg
        val fallback: String

        // try to read the files
        try {
            base = File(Constants.OPENMW_BASE_CFG).readText()
            // TODO: support user custom options
            fallback = File(Constants.OPENMW_FALLBACK_CFG).readText()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read openmw.base.cfg or openmw.fallback.cfg", e)
            return
        }

        // Portable build.ini is the launcher source of truth. If it does not
        // exist yet, create it from the current Mods database once.
        val manifest = BuildManifest.ensure(this)

        try {
            var output = base + "\n" + fallback + "\n"
            manifest.archives.forEach { output += "fallback-archive=$it\n" }
            manifest.content.forEach { output += "content=$it\n" }
            manifest.groundcover.forEach { output += "groundcover=$it\n" }

            // write everything to openmw.cfg
            File(Constants.OPENMW_CFG).writeText(output)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to generate openmw.cfg.", e)
        }
    }

    /**
     * Determines required screen scaling based on resolution and physical size of the device
     */
    private fun determineScaling(): Float {
        // The idea is to stretch an old-school 1024x768 monitor to the device screen
        // Assume that 1x scaling corresponds to resolution of 1024x768
        // Assume that the longest side of the device corresponds to the 1024 side
        // Therefore scaling is calculated as longest size of the device divided by 1024
        // Note that it doesn't take into account DPI at all. Which is fine for now, but in future
        // we might want to add some bonus scaling to e.g. phone devices so that it's easier
        // to click things.

        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        return maxOf(dm.heightPixels, dm.widthPixels) / 1024.0f
    }

    /**
     * Removes old and creates new files located in private application directories
     * (i.e. under getFilesDir(), or /data/data/.../files)
     */
    private fun normalizeVersionText(value: String): String {
        return value.replace("\r\n", "\n").replace('\r', '\n').trim()
    }

    /**
     * TES3MP uses resources/version as part of the low-level RakNet connection
     * password (version + protocol + commit hash).  VERSION_CODE alone is not a
     * sufficient resource deployment stamp because Android development builds
     * can be rebuilt from a newer AMP commit without changing the Java version.
     */
    private fun bundledResourcesVersion(): String {
        return assets.open("libopenmw/resources/version").bufferedReader().use {
            normalizeVersionText(it.readText())
        }
    }

    private fun installedResourcesVersion(): String {
        val versionFile = File(Constants.RESOURCES, "version")
        if (!versionFile.isFile)
            return ""
        return normalizeVersionText(versionFile.readText())
    }

    private fun staticFilesNeedReinstall(): Boolean {
        val stampMatches = try {
            File(Constants.VERSION_STAMP).readText().trim().toInt() == BuildConfig.VERSION_CODE
        } catch (_: Exception) {
            false
        }

        val bundledVersion = try {
            bundledResourcesVersion()
        } catch (e: Exception) {
            Log.e("ArenaMP", "Could not read bundled resources/version", e)
            return true
        }
        val installedVersion = try {
            installedResourcesVersion()
        } catch (e: Exception) {
            Log.w("ArenaMP", "Could not read installed resources/version", e)
            ""
        }

        val resourceVersionMatches = bundledVersion.isNotEmpty() && bundledVersion == installedVersion
        if (!stampMatches || !resourceVersionMatches) {
            val bundledCommit = bundledVersion.lineSequence().drop(1).firstOrNull().orEmpty().take(10)
            val installedCommit = installedVersion.lineSequence().drop(1).firstOrNull().orEmpty().take(10)
            Log.i(
                "ArenaMP",
                "Static resource refresh required: versionCodeOk=$stampMatches " +
                    "bundledCommit=$bundledCommit installedCommit=$installedCommit"
            )
        }
        return !stampMatches || !resourceVersionMatches
    }

    private fun reinstallStaticFiles() {
        // we store global "config" and "resources" under private files

        // wipe old version first
        removeStaticFiles()

        // copy in the new version
        val assetCopier = CopyFilesFromAssets(this)
        assetCopier.copy("libopenmw/resources", Constants.RESOURCES)
        assetCopier.copy("libopenmw/openmw", Constants.GLOBAL_CONFIG)

        // set up user config (if not present)
        File(Constants.USER_CONFIG).mkdirs()
        if (!File(Constants.USER_OPENMW_CFG).exists())
            File(Constants.USER_OPENMW_CFG).writeText("# This is the user openmw.cfg. Feel free to modify it as you wish.\n")

        // set version stamp only after the complete resource/config copy succeeds.
        File(Constants.VERSION_STAMP).writeText(BuildConfig.VERSION_CODE.toString())
    }

    /**
     * Removes global static files, these include resources and config
     */
    private fun removeStaticFiles() {
        // remove version stamp so that reinstallStaticFiles is called during game launch
        File(Constants.VERSION_STAMP).delete()

        deleteRecursive(File(Constants.GLOBAL_CONFIG))
        deleteRecursive(File(Constants.RESOURCES))
    }

    /**
     * Resets user config to default values by removing it
     */
    private fun removeUserConfig() {
        deleteRecursive(File(Constants.USER_CONFIG))
    }

    private fun configureDefaultsBin(args: Map<String, String>) {
        val defaults = File(Constants.DEFAULTS_BIN).readText()
        val decoded = String(Base64.getDecoder().decode(defaults))
        val lines = decoded.lines().map {
            for ((k, v) in args) {
                if (it.startsWith("$k ="))
                    return@map "$k = $v"
            }
            it
        }
        val data = lines.joinToString("\n")
        val encoded = Base64.getEncoder().encodeToString(data.toByteArray())
        File(Constants.DEFAULTS_BIN).writeText(encoded)
    }

    private fun startGame() {
//***********************************************************************************************************************************************************

        // GUI scale is no longer exposed in the Android launcher. Keep the
        // automatic device-derived scale and drop any stale legacy override.
        val scaling = MyApp.app.defaultScaling
        if (prefs.contains("pref_uiScaling"))
            prefs.edit().remove("pref_uiScaling").apply()

        // set up gamma, if invalid, use the default (1.0)
        var gamma = 1.0f
        try {
            gamma = prefs.getString("pref_gamma", "")!!.toFloat()
        } catch (e: NumberFormatException) {
            // Reset the invalid setting
            with(prefs.edit()) {
                putString("pref_gamma", "")
                apply()
            }
        }

        try {
            Os.setenv("OPENMW_GAMMA", "%.2f".format(Locale.ROOT, gamma), true)
        } catch (e: ErrnoException) {
            // can't really do much if that fails...
        }

        val dialog = ProgressDialog.show(
            this, "", "Preparing for launch...", true)

        val activity = this

        // hide the controls so that ScreenResolutionHelper can get the right resolution
        hideAndroidControls(this)

        val th = Thread {
            var launchOk = false
            try {
                // Keep the deployed resources/version synchronized with the native
                // ArenaMP build. This commit hash is part of the TES3MP connection
                // identity, so stale resources cause a misleading "Version mismatch".
                if (staticFilesNeedReinstall()) {
                    reinstallStaticFiles()
                }

                val inst = GameInstaller(prefs.getString("game_files", "")!!)

                // Regenerate the fallback file in case user edits their Morrowind.ini
                inst.convertIni(prefs.getString("pref_encoding", GameInstaller.DEFAULT_CHARSET_PREF)!!)

                // Persist editable IP/port and current content order. complete=true
                // preserves the endpoint supplied by the package manifest.
                BuildManifest.writeFromDatabase(this)
                generateOpenmwCfg()

                // openmw.cfg: data, resources
                file.Writer.write(Constants.OPENMW_CFG, "resources", Constants.RESOURCES)
                file.Writer.write(Constants.OPENMW_CFG, "data", "\"" + inst.findDataFiles() + "\"")

                file.Writer.write(Constants.OPENMW_CFG, "encoding", prefs!!.getString("pref_encoding", GameInstaller.DEFAULT_CHARSET_PREF)!!)

                // --- надёжное создание папки пользовательских настроек ---
                val settingsFolder = File(Constants.USER_CONFIG)
                if (!settingsFolder.exists() && !settingsFolder.mkdirs()) {
                    // иногда mkdirs возвращает false сразу после grant'а разрешения:
                    // подождём короткий момент и попробуем снова
                    try { Thread.sleep(150) } catch (_: InterruptedException) {}
                    settingsFolder.mkdirs()
                }
                if (!settingsFolder.isDirectory) {
                    throw IOException("Cannot create user config directory: ${settingsFolder.absolutePath}")
                }

                val settingsFile = File(settingsFolder, "settings.cfg")
                val settingsFileCreated: Boolean = settingsFile.createNewFile()
                if (settingsFileCreated) {
                    // шаблон может отсутствовать в ассетах — тогда оставляем файл пустым,
                    // Writer.write ниже сам наполнит его нужными ключами
                    val template = File(filesDir, "config/settings.cfg")
                    if (template.exists()) {
                        try {
                            settingsFile.writeText(template.readText())
                        } catch (e: IOException) {
                            Log.w(TAG, "Failed to copy settings.cfg template, continuing with empty file", e)
                        }
                    }
                }

                val enabler = prefs.getBoolean("pref_global_functions", false)
                if (enabler) {

                val cfg = Constants.USER_CONFIG + "/settings.cfg"
                fun boolPref(key: String, default: Boolean = false) =
                    if (prefs.getBoolean(key, default)) "true" else "false"

                // [GUI]
                file.Writer.write(cfg, "GUI", "scaling factor", "%.2f".format(Locale.ROOT, scaling))
                file.Writer.write(cfg, "GUI", "font size", prefs.getString("pref_font_size", "15")!!)
                file.Writer.write(cfg, "GUI", "stretch menu background", boolPref("pref_stretch_menu_background"))
                file.Writer.write(cfg, "GUI", "subtitles", boolPref("pref_subtitles"))
                file.Writer.write(cfg, "GUI", "color topic enable", boolPref("pref_color_topic_enable"))
                file.Writer.write(cfg, "GUI", "color topic specific", prefs.getString("pref_color_topic_specific", "0.45 0.5 0.8 1")!!)
                file.Writer.write(cfg, "GUI", "color topic exhausted", prefs.getString("pref_color_topic_exhausted", "0.3 0.3 0.3 1")!!)

                // [Video]
                file.Writer.write(cfg, "Video", "antialiasing", prefs.getString("pref_antialiasing", "0")!!)
                file.Writer.write(cfg, "Video", "vsync", boolPref("pref_vsync"))
                file.Writer.write(cfg, "Video", "framerate limit", prefs.getString("pref_framerate_limit", "120")!!)

                // [Camera]
                file.Writer.write(cfg, "Camera", "viewing distance", prefs.getString("pref_viewing_distance", "8192")!!)
                file.Writer.write(cfg, "Camera", "small feature culling", boolPref("pref_small_feature_culling"))
                file.Writer.write(cfg, "Camera", "small feature culling pixel size", prefs.getString("pref_small_feature_culling_pixel_size", "12")!!)
                file.Writer.write(cfg, "Camera", "head bobbing", boolPref("pref_head_bobbing"))
                file.Writer.write(cfg, "Camera", "view over shoulder", boolPref("pref_view_over_shoulder"))
                file.Writer.write(cfg, "Camera", "auto switch shoulder", boolPref("pref_auto_switch_shoulder"))
                file.Writer.write(cfg, "Camera", "preview if stand still", boolPref("pref_preview_if_stand_still"))
                file.Writer.write(cfg, "Camera", "deferred preview rotation", boolPref("pref_deferred_preview_rotation"))
                file.Writer.write(cfg, "Camera", "occlusion culling", boolPref("pref_occlusion_culling"))

                // [Cells]
                file.Writer.write(cfg, "Cells", "preload enabled", boolPref("pref_preloading"))
                file.Writer.write(cfg, "Cells", "preload num threads", prefs.getString("pref_preload_num_threads", "1")!!)
                file.Writer.write(cfg, "Cells", "preload distance", "1000")
                file.Writer.write(cfg, "Cells", "preload exterior grid", boolPref("pref_preload_exterior_grid"))
                file.Writer.write(cfg, "Cells", "preload fast travel", boolPref("pref_preload_fast_travel"))
                file.Writer.write(cfg, "Cells", "preload doors", boolPref("pref_preload_doors"))
                file.Writer.write(cfg, "Cells", "preload instances", boolPref("pref_preload_instances"))
                file.Writer.write(cfg, "Cells", "target framerate", prefs.getString("pref_target_framerate", "30")!!)

                // [Terrain]
                file.Writer.write(cfg, "Terrain", "distant terrain", boolPref("pref_distant"))
                file.Writer.write(cfg, "Terrain", "lod factor", prefs.getString("pref_lod_factor", "0.4")!!)
                file.Writer.write(cfg, "Terrain", "vertex lod mod", prefs.getString("pref_vertex_lod_mod", "-2")!!)
                file.Writer.write(cfg, "Terrain", "composite map level", prefs.getString("pref_composite_map_level", "-3")!!)
                file.Writer.write(cfg, "Terrain", "object paging", boolPref("pref_object_paging"))
                file.Writer.write(cfg, "Terrain", "object paging merge factor", prefs.getString("pref_object_paging_merge_factor", "100000")!!)
                file.Writer.write(cfg, "Terrain", "object paging min size", prefs.getString("pref_object_paging_min_size", "1")!!)

                // [Shaders]
                file.Writer.write(cfg, "Shaders", "force shaders", boolPref("pref_shaders"))
                file.Writer.write(cfg, "Shaders", "force per pixel lighting", boolPref("pref_pix_light"))
                file.Writer.write(cfg, "Shaders", "clamp lighting", boolPref("pref_clamp_lighting"))
                file.Writer.write(cfg, "Shaders", "radial fog", boolPref("pref_radfog"))
                file.Writer.write(cfg, "Shaders", "lighting method", prefs.getString("pref_lighting_method", "shaders compatibility")!!)
                file.Writer.write(cfg, "Shaders", "max lights", prefs.getString("pref_max_lights", "16")!!)
                file.Writer.write(cfg, "Shaders", "minimum interior brightness", prefs.getString("pref_minimum_interior_brightness", "0.16")!!)
                file.Writer.write(cfg, "Shaders", "auto use object normal maps", boolPref("pref_auto_use_pbr"))
                file.Writer.write(cfg, "Shaders", "auto use object specular maps", boolPref("pref_auto_use_pbr"))
                file.Writer.write(cfg, "Shaders", "auto use terrain normal maps", boolPref("pref_auto_use_pbr"))
                file.Writer.write(cfg, "Shaders", "auto use terrain specular maps", boolPref("pref_auto_use_pbr"))
                file.Writer.write(cfg, "Shaders", "apply lighting to environment maps", boolPref("pref_apply_lighting_to_environment_maps"))

                // [Water]
                file.Writer.write(cfg, "Water", "shader", boolPref("pref_shader_water"))
                file.Writer.write(cfg, "Water", "refraction", boolPref("pref_refraction"))
                file.Writer.write(cfg, "Water", "rtt size", prefs.getString("pref_rtt_size", "256")!!)
                file.Writer.write(cfg, "Water", "reflection detail", prefs.getString("pref_reflection_detail", "3")!!)

                // [Input]
                file.Writer.write(cfg, "Input", "enable gyroscope", boolPref("pref_gyroscope"))
                file.Writer.write(cfg, "Input", "toggle sneak", boolPref("pref_toggle_sneak"))
                file.Writer.write(cfg, "Input", "always run", boolPref("pref_always_run"))

                // [Saves]
                file.Writer.write(cfg, "Saves", "max quicksaves", prefs.getString("pref_max_quicksaves", "3")!!)
                file.Writer.write(cfg, "Saves", "autosave", boolPref("pref_autosave"))
                file.Writer.write(cfg, "Saves", "timeplayed", boolPref("pref_timeplayed"))

                // [Navigator]
                file.Writer.write(cfg, "Navigator", "async nav mesh updater threads", prefs.getString("pref_async_nav_mesh_updater_threads", "1")!!)

                // [Physics]
                file.Writer.write(cfg, "Physics", "async num threads", prefs.getString("pref_async", "0")!!)

                // [Models]
                file.Writer.write(cfg, "Models", "load unsupported nif files", boolPref("pref_load_unsupported_nif_files"))

                // [Groundcover]
                file.Writer.write(cfg, "Groundcover", "enabled", boolPref("pref_groundcover_enable"))
                file.Writer.write(cfg, "Groundcover", "density", prefs.getString("pref_density", "0.9")!!)
                file.Writer.write(cfg, "Groundcover", "rendering distance", prefs.getString("pref_rendering_distance", "8192.0")!!)
                file.Writer.write(cfg, "Groundcover", "min chunk size", prefs.getString("pref_min_chunk_size", "0.5")!!)
                file.Writer.write(cfg, "Groundcover", "stomp mode", prefs.getString("pref_stomp_mode", "1")!!)
                file.Writer.write(cfg, "Groundcover", "stomp intensity", prefs.getString("pref_stomp_intensity", "1")!!)

                // [Game]
                file.Writer.write(cfg, "Game", "show owned", prefs.getString("pref_show_owned", "3")!!)
                file.Writer.write(cfg, "Game", "difficulty", prefs.getString("pref_difficulty", "0")!!)
                file.Writer.write(cfg, "Game", "strength influences hand to hand", prefs.getString("pref_strength_influences_hand_to_hand", "2")!!)
                file.Writer.write(cfg, "Game", "projectiles enchant multiplier", prefs.getString("pref_projectiles_enchant_multiplier", "0")!!)
                file.Writer.write(cfg, "Game", "show projectile damage", boolPref("pref_show_projectile_damage"))
                file.Writer.write(cfg, "Game", "show melee info", boolPref("pref_show_melee_info"))
                file.Writer.write(cfg, "Game", "show enchant chance", boolPref("pref_show_enchant_chance"))
                file.Writer.write(cfg, "Game", "best attack", boolPref("pref_best_attack"))
                file.Writer.write(cfg, "Game", "classic reflected absorb spells behavior", boolPref("pref_classic_reflected_absorb_spells_behavior"))
                file.Writer.write(cfg, "Game", "show effect duration", boolPref("pref_show_effect_duration"))
                file.Writer.write(cfg, "Game", "prevent merchant equipping", boolPref("pref_prevent_merchant_equipping"))
                file.Writer.write(cfg, "Game", "enchanted weapons are magical", boolPref("pref_enchanted_weapons_are_magical"))
                file.Writer.write(cfg, "Game", "followers attack on sight", boolPref("pref_followers_attack_on_sight"))
                file.Writer.write(cfg, "Game", "can loot during death animation", boolPref("pref_can_loot_during_death_animation"))
                file.Writer.write(cfg, "Game", "rebalance soul gem values", boolPref("pref_rebalance_soul_gem_values"))
                file.Writer.write(cfg, "Game", "use additional anim sources", boolPref("pref_use_additional_anim_sources"))
                file.Writer.write(cfg, "Game", "barter disposition change is permanent", boolPref("pref_barter_disposition_change_is_permanent"))
                file.Writer.write(cfg, "Game", "weapon sheathing", boolPref("pref_weapon_sheathing"))
                file.Writer.write(cfg, "Game", "shield sheathing", boolPref("pref_shield_sheathing"))
                file.Writer.write(cfg, "Game", "only appropriate ammunition bypasses resistance", boolPref("pref_only_appropriate_ammunition_bypasses_resistance"))
                file.Writer.write(cfg, "Game", "use magic item animations", boolPref("pref_use_magic_item_animations"))
                file.Writer.write(cfg, "Game", "normalise race speed", boolPref("pref_normalise_race_speed"))
                file.Writer.write(cfg, "Game", "uncapped damage fatigue", boolPref("pref_uncapped_damage_fatigue"))
                file.Writer.write(cfg, "Game", "turn to movement direction", boolPref("pref_turn_to_movement_direction"))
                file.Writer.write(cfg, "Game", "smooth movement", boolPref("pref_smooth_movement"))
                file.Writer.write(cfg, "Game", "swim upward correction", boolPref("pref_swim_upward_correction"))
                file.Writer.write(cfg, "Game", "trainers training skills based on base skill", boolPref("pref_trainers_training_skills_based_on_base_skill"))
                file.Writer.write(cfg, "Game", "always allow stealing from knocked out actors", boolPref("pref_always_allow_stealing_from_knocked_out_actors"))
                file.Writer.write(cfg, "Game", "graphic herbalism", boolPref("pref_graphic_herbalism"))
                file.Writer.write(cfg, "Game", "allow actors to follow over water surface", boolPref("pref_allow_actors_to_follow_over_water_surface"))
                }

                // Preserve the stock OpenMW actor-collision behaviour on Android.
                // These preferences are not exposed in the current launcher; older
                // code accidentally treated the missing prefs as false and wrote
                // that into settings.cfg, which lets NPCs overlap each other.
                val settingsCfgPath = Constants.USER_CONFIG + "/settings.cfg"
                file.Writer.write(settingsCfgPath, "Game", "NPCs avoid collisions", "true")
                file.Writer.write(settingsCfgPath, "Game", "NPCs give way", "true")

                // Launcher graphics window is authoritative for the Android
                // mobile profile. Apply it last so legacy/hidden preferences cannot
                // silently overwrite the values the user selected.
                GraphicsPresets.applyToSettings(prefs)

                configureDefaultsBin(mapOf(

                        "camera sensitivity" to "0.4"
                ))

                launchOk = true
                runOnUiThread {
                    obtainFixedScreenResolution()
                    try { dialog.dismiss() } catch (_: Exception) {}
                    runGame()
                }
            } catch (e: Throwable) {
                // Ловим ВСЁ, включая RuntimeException, иначе диалог останется висеть навсегда
                Log.e(TAG, "Failed to prepare game launch.", e)
                val msg = e.message ?: e.javaClass.simpleName
                runOnUiThread {
                    try { dialog.dismiss() } catch (_: Exception) {}
                    Toast.makeText(
                        activity,
                        "Не удалось запустить игру: $msg",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                // страховка на случай, если что-то пойдёт не так даже в runOnUiThread выше
                if (!launchOk) {
                    runOnUiThread {
                        try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {}
                    }
                }
            }
        }
        th.start()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        val inflater = menuInflater
        inflater.inflate(R.menu.menu_settings, menu)
        if (!MyApp.haveBugsnagApiKey)
            menu.findItem(R.id.action_bugsnag_consent).setVisible(false)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reset_config -> {
                removeUserConfig()
                removeStaticFiles()
                Toast.makeText(this, getString(R.string.config_was_reset), Toast.LENGTH_SHORT).show()
                true
            }

            R.id.action_about -> {
                val text = assets.open("libopenmw/3rdparty-licenses.txt")
                    .bufferedReader()
                    .use { it.readText() }

                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.about_title))
                    .setMessage(text)
                    .show()

                true
            }

            R.id.action_bugsnag_consent -> {
                askBugsnagConsent()
                true
            }

            R.id.action_change_update_server -> {
                showChangeUpdateServerDialog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Shows a dialog to change the update-server URL.
     */
    private fun showChangeUpdateServerDialog() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(
                prefs.getString(
                    UpdateDownloader.PREF_UPDATE_URL,
                    UpdateDownloader.DEFAULT_UPDATE_URL
                )
            )
            setSelection(text.length)
        }

        val container = android.widget.FrameLayout(this).apply {
            val pad = (resources.displayMetrics.density * 16).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.update_server_dialog_title)
            .setMessage(R.string.update_server_dialog_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text.toString().trim()
                val finalUrl = if (raw.isEmpty()) UpdateDownloader.DEFAULT_UPDATE_URL else raw
                with (prefs.edit()) {
                    putString(UpdateDownloader.PREF_UPDATE_URL, finalUrl)
                    apply()
                }
                Toast.makeText(this, getString(R.string.update_server_saved, finalUrl), Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.update_server_reset) { _, _ ->
                with (prefs.edit()) {
                    putString(UpdateDownloader.PREF_UPDATE_URL, UpdateDownloader.DEFAULT_UPDATE_URL)
                    apply()
                }
                Toast.makeText(this, getString(R.string.update_server_saved, UpdateDownloader.DEFAULT_UPDATE_URL), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val TAG = "OpenMW-Launcher"

        var resolutionX = 0
        var resolutionY = 0
    }
}
