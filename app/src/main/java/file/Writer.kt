/*
    Copyright (C) 2016 sandstranger
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

import java.io.File
import java.io.IOException

object Writer {

    /**
     * Writes key = value into the correct [section] of an INI-style config file.
     * - If the section exists and contains the key → updates in-place.
     * - If the section exists but doesn't contain the key → inserts right after the section header.
     * - If the section doesn't exist → appends [section] + key = value at the end.
     *
     * @param path    Path to the config file (created if absent).
     * @param section Section name without brackets, e.g. "Video".
     * @param key     Key name, e.g. "antialiasing".
     * @param value   Value string.
     */
    @Throws(IOException::class)
    fun write(path: String, section: String, key: String, value: String) {
        val file = File(path)
        if (!file.exists()) file.createNewFile()

        val lines = file.readLines().toMutableList()
        val sectionHeader = "[$section]"

        var sectionIdx = -1
        var keyIdx = -1

        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.equals(sectionHeader, ignoreCase = true)) {
                sectionIdx = i
            } else if (sectionIdx >= 0 && keyIdx < 0) {
                // We are inside our section — check for key match
                val withoutComment = trimmed.substringBefore('#').trim()
                if (withoutComment.startsWith(key)) {
                    val rest = withoutComment.removePrefix(key).trimStart()
                    if (rest.startsWith("=")) {
                        keyIdx = i
                        break
                    }
                }
                // Hit next section — stop searching
                if (trimmed.startsWith("[")) break
            }
        }

        when {
            keyIdx >= 0 -> {
                // Update existing key
                lines[keyIdx] = "$key = $value"
            }
            sectionIdx >= 0 -> {
                // Insert key right after section header
                lines.add(sectionIdx + 1, "$key = $value")
            }
            else -> {
                // Append new section + key
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                lines.add(sectionHeader)
                lines.add("$key = $value")
            }
        }

        file.writeText(lines.joinToString("\n"))
    }

    // ---------------------------------------------------------------------------
    // Legacy overload — kept for callers that don't need section awareness
    // (e.g. openmw.cfg which uses a flat format).
    // ---------------------------------------------------------------------------
    @Throws(IOException::class)
    fun write(path: String, key: String, value: String) {
        val fin = File(path)
        fin.createNewFile()

        val lines = fin.readLines().toMutableList()
        var contains = false

        for (i in lines.indices) {
            if (lines[i].startsWith(key) && !contains) {
                lines[i] = "$key=$value"
                contains = true
            }
        }
        if (!contains) lines.add("$key=$value")

        fin.writeText(lines.joinToString("\n"))
    }
}
