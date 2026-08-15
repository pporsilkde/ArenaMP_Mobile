/*
    Copyright (C) 2016 sandstranger
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

package ui.fragments

import android.Manifest
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.ListView
import android.preference.EditTextPreference
import android.preference.Preference
import android.preference.PreferenceFragment
import android.preference.PreferenceGroup
import androidx.core.content.ContextCompat

import com.codekidlabs.storagechooser.StorageChooser
import com.libopenmw.openmw.R
import file.GameInstaller
import file.BuildManifest

import ui.activity.ConfigureControls
import ui.activity.MainActivity
import ui.activity.ModsActivity
import ui.activity.GraphicsSettingsActivity
import utils.MyApp
import java.util.*

class FragmentSettings : PreferenceFragment(), OnSharedPreferenceChangeListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        addPreferencesFromResource(R.xml.settings)
        // Desktop-compatible build.ini is authoritative on first load. Import the
        // endpoint before registering the listener so this sync is not written back.
        BuildManifest.syncConnectionPreferences(activity)
        preferenceScreen.sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        updateGammaState()
        updateServerLockState()

        findPreference("pref_controls").setOnPreferenceClickListener {
            val intent = Intent(activity, ConfigureControls::class.java)
            this.startActivity(intent)
            true
        }

        findPreference("pref_mods").setOnPreferenceClickListener {
            val intent = Intent(activity, ModsActivity::class.java)
            this.startActivity(intent)
            true
        }

        findPreference("pref_graphics_settings").setOnPreferenceClickListener {
            val intent = Intent(activity, GraphicsSettingsActivity::class.java)
            this.startActivity(intent)
            true
        }

        findPreference("game_files").setOnPreferenceClickListener {
            if (ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                showError(R.string.permissions_error_title, R.string.permissions_error_message)
            } else {
                val chooser = StorageChooser.Builder()
                    .withActivity(activity)
                    .withFragmentManager(fragmentManager)
                    .withMemoryBar(true)
                    .allowCustomPath(true)
                    .setType(StorageChooser.DIRECTORY_CHOOSER)
                    .build()

                chooser.show()

                chooser.setOnSelectListener { path -> setupData(path) }
            }
            true
        }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Make legacy PreferenceFragment readable on AMOLED: distinct rows and
        // breathing room instead of one continuous dark block.
        val list = view.findViewById<ListView>(android.R.id.list) ?: return
        val density = resources.displayMetrics.density
        list.setPadding((10 * density).toInt(), (6 * density).toInt(),
            (10 * density).toInt(), (84 * density).toInt())
        list.clipToPadding = false
        list.divider = ColorDrawable(ContextCompat.getColor(activity, R.color.bgDivider))
        list.dividerHeight = (1 * density).toInt().coerceAtLeast(1)
        list.setSelector(android.R.color.transparent)
        list.setBackgroundColor(ContextCompat.getColor(activity, R.color.bgPrimary))
    }

    /**
     * Checks the specified path for a valid morrowind installation, generates config files
     * and saves the path to shared prefs if it's valid.
     * If it isn't, an error is displayed to the user.
     */
    private fun setupData(path: String) {
        val sharedPref = preferenceScreen.sharedPreferences

        // reset the setting so that it's erased on error instead of keeping
        // possibly stale value
        var gameFiles = ""

        val inst = GameInstaller(path)
        if (inst.check()) {
            inst.setNomedia()
            if (!inst.convertIni(sharedPref.getString("pref_encoding", GameInstaller.DEFAULT_CHARSET_PREF)!!)) {
                showError(R.string.data_error_title, R.string.ini_error_message)
            } else {
                gameFiles = path
            }
        } else {
            showError(R.string.data_error_title, R.string.data_error_message,
                    "https://omw.xyz.is/game.html")
        }

        with(sharedPref.edit()) {
            putString("game_files", gameFiles)
            apply()
        }
        if (gameFiles.isNotEmpty()) {
            BuildManifest.syncConnectionPreferences(activity)
            updateServerLockState()
        }
    }

    /**
     * Shows an alert dialog displaying a specific error
     * @param title Title string resource
     * @param message Message string resource
     */
    private fun showError(title: Int, message: Int, url: String? = null) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface, _: Int -> }

        if (url != null) {
            dialog.setNeutralButton(R.string.dialog_howto) { _, _ ->
                (activity as MainActivity).openUrl(url)
            }
        }

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        for (i in 0 until preferenceScreen.preferenceCount) {
            val preference = preferenceScreen.getPreference(i)
            if (preference is PreferenceGroup) {
                for (j in 0 until preference.preferenceCount) {
                    val singlePref = preference.getPreference(j)
                    updatePreference(singlePref, singlePref.key)
                }
            } else {
                updatePreference(preference, preference.key)
            }
        }
        updateServerLockState()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        updatePreference(findPreference(key), key)
        if (key == "pref_server_ip" || key == "pref_server_port")
            BuildManifest.updateConnectionFromPreferences(activity)
        updateGammaState()
        updateServerLockState()
    }

    private fun updatePreference(preference: Preference?, key: String) {
        if (preference == null)
            return
        if (preference is EditTextPreference) {
            if (key == "pref_uiScaling" && (preference.text == null || preference.text.isEmpty()))
                // Show "Auto (1.23)"
                preference.summary = MyApp.app.getString(R.string.uiScaling_auto)
                    .format(Locale.ROOT, MyApp.app.defaultScaling)
            else
                preference.summary = preference.text
        }
        // Show selected value as a summary for game_files
        if (key == "game_files") {
            preference.summary = preference.sharedPreferences.getString("game_files", "")
        }
    }


    private fun updateServerLockState() {
        val manifest = BuildManifest.read(activity)
        val locked = manifest?.complete == true
        val ip = findPreference("pref_server_ip")
        val port = findPreference("pref_server_port")
        ip?.isEnabled = !locked
        port?.isEnabled = !locked
        if (locked) {
            ip?.summary = getString(R.string.pref_server_locked) + " · " + (manifest?.serverAddress ?: BuildManifest.DEFAULT_SERVER_ADDRESS)
            port?.summary = getString(R.string.pref_server_locked) + " · " + (manifest?.serverPort ?: BuildManifest.DEFAULT_SERVER_PORT)
        } else {
            updatePreference(ip, "pref_server_ip")
            updatePreference(port, "pref_server_port")
        }
    }

    /**
     * @brief Disable gamma preference if GLES1 is selected
     */
    private fun updateGammaState() {
        val sharedPref = preferenceScreen.sharedPreferences
        findPreference("pref_gamma").isEnabled =
                sharedPref.getString("pref_graphicsLibrary_v2", "") != "gles1"
    }

}
