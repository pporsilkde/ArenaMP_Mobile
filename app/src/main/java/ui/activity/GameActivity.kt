/*
    Copyright (C) 2015-2017 sandstranger
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

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import android.preference.PreferenceManager
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import android.view.WindowManager
import android.widget.RelativeLayout
import com.libopenmw.openmw.BuildConfig
import com.libopenmw.openmw.R

import org.libsdl.app.SDLActivity

import constants.Constants
import cursor.MouseCursor
import file.GraphicsPresets
import file.BuildManifest
import parser.CommandlineParser
import ui.controls.Osc

import utils.Utils.hideAndroidControls

enum class MouseMode {
    Hybrid,
    Joystick,
    Touch;

    companion object {
        fun get(s: String): MouseMode {
            return when (s) {
                "joystick" -> Joystick
                "touch" -> Touch
                else -> Hybrid
            }
        }
    }
}

class GameActivity : SDLActivity() {

    private var prefs: SharedPreferences? = null
    private var osc: Osc? = null

    val layout: RelativeLayout
        get() = SDLActivity.mLayout as RelativeLayout

    override fun loadLibraries() {
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val physicsFPS = prefs!!.getString("pref_physicsFPS2", "")

        // ── Physics FPS ───────────────────────────────────────────────────────
        if (!physicsFPS!!.isEmpty()) {
            try {
                Os.setenv("OPENMW_PHYSICS_FPS", physicsFPS, true)
            } catch (e: ErrnoException) {
                Log.e("OpenMW", "Failed setting OPENMW_PHYSICS_FPS.")
                e.printStackTrace()
            }
        }

        System.loadLibrary("c++_shared")
        System.loadLibrary("openal")
        System.loadLibrary("SDL2")

        try {
            // ── NG-GL4ES требует GLES3 ────────────────────────────────────────
            // В отличие от старого gl4es (GLES2), NG-GL4ES использует GLES3
            // для поддержки sampler2DShadow, shadow2D, UBO и SPIRV-Cross конвертации
            Os.setenv("OPENMW_GLES_VERSION", "3", true)
            Os.setenv("LIBGL_ES", "3", true)

            // ── OSG ───────────────────────────────────────────────────────────
            Os.setenv("OSG_VERTEX_BUFFER_HINT", "VBO", true)
            // Отключить storage textures — NG-GL4ES их не поддерживает через SPIRV
            Os.setenv("OSG_GL_TEXTURE_STORAGE", "OFF", true)
            // ALL позволяет OSG использовать шейдеры для текста (NG-GL4ES их конвертирует)
            Os.setenv("OSG_TEXT_SHADER_TECHNIQUE", "ALL", true)
            Os.setenv("OSG_MIN_NEAR_FAR_RATIO", "0.0001", true)
            Os.setenv("OSG_COP_VALUE", "0x00000100", true)
            Os.setenv("OSG_NOTIFY_LEVEL", "WARN", true)

            // ── OSG Threading из preset ───────────────────────────────────────
            val preset = GraphicsPresets.resolve(prefs!!)
            Os.setenv("OSG_MAX_PAGEDLOD", preset.maxPagedLOD.toString(), true)
            Os.setenv("OSG_THREADING", preset.osgThreading, true)
            Os.setenv("OSG_NUM_DATABASE_THREADS", preset.dbThreads.toString(), true)
            Os.setenv("OSG_NUM_COMPILE_THREADS", preset.compileThreads.toString(), true)
            Os.setenv("OSG_DATABASE_PAGER_THREADS", preset.pagerThreads.toString(), true)
            Os.setenv("OSG_SHADER_CACHE_ENABLED", if (preset.shaderCache) "1" else "0", true)

            // ── NG-GL4ES специфичные переменные ──────────────────────────────
            // Keep the known-working simple converter for the general ArenaMP/OpenMW 0.47 renderer.
            // Complex water V3 is adapted at shader-source level instead of globally forcing
            // the unstable advanced converter that previously produced a purple frame/crash.
            Os.setenv("LIBGL_SIMPLE_SHADERCONV", "1", true)
            // Instancing через SPIRV
            Os.setenv("LIBGL_INSTANCING", "1", true)
            // Match the newer OpenMW-Android builder: keep FBO attachments on the
            // normal texture-backed path instead of forcing GL4ES FBO textures.
            Os.setenv("LIBGL_FBOFORCETEX", "0", true)
            // DXT mipmaps через NG-GL4ES
            Os.setenv("LIBGL_DXTMIPMAP", "1", true)
            // Текстуры
            Os.setenv("LIBGL_AVOID16BITS", "1", true)
            // Avoid per-frame GL4ES diagnostic overhead in normal play while
            // preserving shader compile/link errors. DEBUG/VERBOSE can re-enable it.
            val debugLevel = prefs!!.getString("pref_debug_level", "WARNING") ?: "WARNING"
            Os.setenv("LIBGL_LOG", if (debugLevel == "DEBUG" || debugLevel == "VERBOSE") "1" else "0", true)
            Os.setenv("LIBGL_LOGSHADERERROR", "1", true)
            Os.setenv("OPENMW_DISABLE_LOGS", "0", true)

            // Deliberately do not use LIBGL_SHRINK. Runtime texture shrinking adds
            // conversion work and is not a safe camera-stutter optimization.

        } catch (e: ErrnoException) {
            Log.e("OpenMW", "Failed setting NG-GL4ES environment variables.")
            e.printStackTrace()
        }

        // ── Debug level OpenMW ────────────────────────────────────────────────
        val omwDebugLevel = prefs!!.getString("pref_debug_level", "")
        if (omwDebugLevel == "DEBUG")   Os.setenv("OPENMW_DEBUG_LEVEL", "DEBUG",   true)
        if (omwDebugLevel == "VERBOSE") Os.setenv("OPENMW_DEBUG_LEVEL", "VERBOSE", true)
        if (omwDebugLevel == "INFO")    Os.setenv("OPENMW_DEBUG_LEVEL", "INFO",    true)
        if (omwDebugLevel == "WARNING") Os.setenv("OPENMW_DEBUG_LEVEL", "WARNING", true)
        if (omwDebugLevel == "ERROR")   Os.setenv("OPENMW_DEBUG_LEVEL", "ERROR",   true)

        // ── Пользовательские env переменные ──────────────────────────────────
        val envline = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("envLine", "").toString()
        if (envline.isNotEmpty()) {
            val envs = envline.split(" ", "\n")
            for (entry in envs) {
                val kv = entry.split("=")
                if (kv.size == 2) Os.setenv(kv[0], kv[1], true)
            }
        }

        // ── Gamma ─────────────────────────────────────────────────────────────
        val gamma = prefs!!.getString("pref_gamma", "1.0") ?: "1.0"
        try {
            Os.setenv("OPENMW_GAMMA", gamma, true)
        } catch (e: ErrnoException) {
            Log.e("OpenMW", "Failed setting OPENMW_GAMMA.")
            e.printStackTrace()
        }

        // NG-GL4ES: загружаем libng_gl4es.so вместо старого libGL.so
        System.loadLibrary("ng_gl4es")
        System.loadLibrary("tes3mp")
    }

    override fun getMainSharedObject(): String {
        return applicationInfo.nativeLibraryDir + "/libtes3mp.so"
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeepScreenOn()
        getPathToJni(filesDir.parent, Constants.USER_FILE_STORAGE)
        showControls()
    }

    private fun showControls() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        mouseMode = MouseMode.get((prefs.getString("pref_mouse_mode",
            getString(R.string.pref_mouse_mode_default))!!))

        val pref_hide_controls = prefs.getBoolean(Constants.HIDE_CONTROLS, false)
        if (!pref_hide_controls) {
            val layout = layout
            // Keep left movement, right look and action-button fingers in separate
            // child streams. This prevents pointer-index stealing during multi-touch.
            layout.setMotionEventSplittingEnabled(true)
            osc = Osc()
            osc?.placeElements(layout)
        }
        activeOsc = osc
        MouseCursor(this, osc)
    }

    private fun KeepScreenOn() {
        val needKeepScreenOn = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("pref_screen_keeper", false)
        if (needKeepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    public override fun onDestroy() {
        finish()
        Process.killProcess(Process.myPid())
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideAndroidControls(this)
        }
    }

    override fun getArguments(): Array<String> {
        val manifest = BuildManifest.ensure(this)
        val localHostMode = intent?.getBooleanExtra(EXTRA_LOCAL_HOST_MODE, false) == true
        val serverIp = intent?.getStringExtra(EXTRA_CONNECT_ADDRESS)?.trim()?.takeIf { it.isNotEmpty() }
            ?: manifest.serverAddress.trim().ifEmpty { BuildManifest.DEFAULT_SERVER_ADDRESS }
        val serverPort = intent?.getStringExtra(EXTRA_CONNECT_PORT)?.trim()?.takeIf { it.isNotEmpty() }
            ?: manifest.serverPort.trim().ifEmpty { BuildManifest.DEFAULT_SERVER_PORT }
        val connectTarget = if (serverPort.isEmpty()) serverIp else "$serverIp:$serverPort"

        val cmd = buildString {
            if (connectTarget.isNotEmpty()) append("--connect=").append(connectTarget)
            // Local-host mode deliberately advertises the native client's own
            // compiled/resource identity. The packaged Android server is built from
            // the exact same ArenaMP source revision, so this stays compatible even
            // when workflow_dispatch builds a newer arenamp_ref.
            if (!localHostMode) {
                if (manifest.vanillaServerCompatibility) {
                    if (isNotEmpty()) append(' ')
                    append("--vanilla-build-server")
                } else {
                    // Android application version is independent from the parent PC ArenaMP
                    // network identity. Remote servers only see these handshake values.
                    if (manifest.networkVersion.isNotBlank()) {
                        if (isNotEmpty()) append(' ')
                        append("--network-version=").append(manifest.networkVersion.trim())
                    }
                    val networkProtocol = manifest.networkProtocol.trim().toIntOrNull()
                        ?.takeIf { it > 0 } ?: BuildManifest.DEFAULT_NETWORK_PROTOCOL.toInt()
                    if (isNotEmpty()) append(' ')
                    append("--network-protocol=").append(networkProtocol)
                    if (manifest.networkCommitHash.isNotBlank()) {
                        if (isNotEmpty()) append(' ')
                        append("--network-commit-hash=").append(manifest.networkCommitHash.trim())
                    }
                }
            }
        }
        return CommandlineParser(cmd).argv
    }

    private external fun getPathToJni(path_global: String, path_user: String)

    companion object {
        const val EXTRA_CONNECT_ADDRESS = "arenamp.connect.address"
        const val EXTRA_CONNECT_PORT = "arenamp.connect.port"
        const val EXTRA_LOCAL_HOST_MODE = "arenamp.local.host.mode"

        var mouseMode = MouseMode.Hybrid
        @JvmStatic var activeOsc: Osc? = null

        @JvmStatic
        fun showOsk(): Boolean {
            val osc = activeOsc ?: return false
            osc.onTextInputRequested()
            return true
        }

        @JvmStatic
        fun hideOsk(): Boolean {
            val osc = activeOsc ?: return false
            osc.onTextInputDismissed()
            return true
        }

        @JvmStatic
        fun isOskVisible(): Boolean {
            return activeOsc?.isKeyboardVisible() ?: false
        }
    }
}
