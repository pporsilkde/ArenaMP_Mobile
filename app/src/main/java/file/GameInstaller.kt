/*
    Copyright (C) 2019 Ilya Zhuravlev

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

package file

import android.content.Context
import android.preference.PreferenceManager
import constants.Constants
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/**
 * Class responsible for initial game setup which involves
 * transforming morrowind.ini into openmw.cfg
 */
class GameInstaller(path: String) {

    val dir = File(path)

    data class IniDataFilesSelection(
        val content: List<String>,
        val archives: List<String>
    )

    private fun charsetForEncoding(encoding: String): Charset {
        return when (encoding) {
            "win1250" -> Charset.forName("windows-1250")
            "win1251" -> Charset.forName("windows-1251")
            else -> Charset.forName("windows-1252")
        }
    }

    /**
     * Lists the root directory and finds a file or directory named "name",
     * doing case-insensitive checks
     * @param name Name to search
     * @return File object if it was found, null otherwise
     */
    private fun findCaseInsensitive(name: String): File? {
        val nameLower = name.toLowerCase()
        return dir
            .list { _, fileName -> fileName.toLowerCase() == nameLower }
            .map { File(dir, it) }
            .firstOrNull()
    }

    /**
     * Checks that the "path" directory contains a morrowind.ini,
     * and that there's a "Data Files" directory
     */
    fun check(): Boolean {
        // Root directory must exist and be a directory
        if (!dir.exists() || !dir.isDirectory)
            return false

        // morrowind.ini as well as data files must exist
        return findCaseInsensitive(INI_NAME) != null
            && findCaseInsensitive(DATA_NAME) != null
    }

    /**
     * Returns path to the Data Files directory as a string
     */
    fun findDataFiles(): String {
        return (findCaseInsensitive(DATA_NAME) ?: File(dir, DATA_NAME)).absolutePath
    }

    /**
     * Adds a .nomedia to the game folder so that it doesn't bloat up the gallery
     * If this fails, then who cares
     */
    fun setNomedia() {
        try {
            val file = File(dir, ".nomedia")
            if (!file.exists())
                file.createNewFile()
        } catch (e: IOException) {
        }
    }

    /**
     * Converts morrowind.ini into openmw format and places it into our resources directory
     * (properly named and everything)
     * @param encoding Game encoding as entered by the user; one of pref_encoding_values
     * @return Whether the conversion succeeded
     */
    fun convertIni(encoding: String): Boolean {
        val file = findCaseInsensitive(INI_NAME) ?: return false

        val contents = file.readText(charsetForEncoding(encoding))
        if (contents.isEmpty())
            return false

        val ini = IniConverter(contents)
        val output = ini.convert()
        // there's gotta be something in the output as well
        if (output.isEmpty())
            return false

        File(File(Constants.OPENMW_FALLBACK_CFG).parent).mkdirs()
        File(Constants.OPENMW_FALLBACK_CFG).writeText(output)

        return true
    }

    /**
     * Reads the original Morrowind.ini [Game Files] / [Archives] selection.
     * This is used only as a first-run fallback when the selected game folder
     * does not already contain an ArenaMP build.ini.
     */
    fun readDataFilesSelection(encoding: String): IniDataFilesSelection? {
        val file = findCaseInsensitive(INI_NAME) ?: return null
        val contents = try {
            file.readText(charsetForEncoding(encoding))
        } catch (_: IOException) {
            return null
        }

        data class OrderedEntry(val order: Int, val sequence: Int, val value: String)

        val content = arrayListOf<OrderedEntry>()
        val archives = arrayListOf<OrderedEntry>()
        var section = ""
        var sequence = 0

        contents.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(";"))
                return@forEach

            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().toLowerCase()
                return@forEach
            }

            val eq = line.indexOf('=')
            if (eq <= 0)
                return@forEach

            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 && value.first() == '"' && value.last() == '"')
                value = value.substring(1, value.length - 1)
            if (value.isBlank())
                return@forEach

            val normalizedKey = key.replace(" ", "").toLowerCase()
            val numericOrder = normalizedKey.filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
            when (section) {
                "game files", "gamefiles" -> if (normalizedKey.startsWith("gamefile"))
                    content.add(OrderedEntry(numericOrder, sequence++, value))
                "archives" -> if (normalizedKey.startsWith("archive"))
                    archives.add(OrderedEntry(numericOrder, sequence++, value))
            }
        }

        val ordering = compareBy<OrderedEntry> { it.order }.thenBy { it.sequence }
        return IniDataFilesSelection(
            content.sortedWith(ordering).map { it.value },
            archives.sortedWith(ordering).map { it.value }
        )
    }

    companion object {
        const val INI_NAME = "Morrowind.ini"
        const val DATA_NAME = "Data Files"
        const val DEFAULT_CHARSET_PREF = "win1252"

        /**
         * Returns path of Data Files, making use of path to the game from the settings
         * @param ctx Android context
         * @return Absolute path to data files as a string
         */
        fun getDataFiles(ctx: Context): String {
            val gamePath = PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString("game_files", "")!!
            val inst = GameInstaller(gamePath)
            return inst.findDataFiles()
        }
    }

}
