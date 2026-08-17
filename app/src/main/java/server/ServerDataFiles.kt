package server

import android.content.Context
import file.BuildManifest
import file.GameInstaller
import java.io.File
import java.io.FileInputStream
import java.util.zip.CRC32

/**
 * Android equivalent of the desktop launcher's "Update Hash" action.
 * Generates server/data/requiredDataFiles.json in the same formatVersion=2
 * order as build.ini / the current Mods database selection.
 */
object ServerDataFiles {
    data class Result(val required: Int, val groundcover: Int, val file: File)

    private fun crc32(file: File): Long {
        val crc = CRC32()
        val buffer = ByteArray(1024 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) crc.update(buffer, 0, count)
            }
        }
        return crc.value
    }

    private fun jsonEscape(value: String): String {
        val out = StringBuilder(value.length + 8)
        value.forEach { ch ->
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.toInt() < 0x20) out.append(String.format("\\u%04X", ch.toInt())) else out.append(ch)
            }
        }
        return out.toString()
    }

    private fun locate(dataFiles: File, name: String): File {
        val direct = File(dataFiles, name)
        if (direct.isFile) return direct
        val actual = dataFiles.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
        return actual ?: throw IllegalStateException("Selected data file was not found: $name")
    }

    private fun entry(name: String, file: File): String {
        val hash = String.format("0x%08X", crc32(file))
        return "{\"${jsonEscape(name)}\":[\"$hash\"]}"
    }

    fun update(ctx: Context): Result {
        ServerRuntime.ensureInstalled(ctx)
        // An existing desktop build.ini is authoritative. Import its exact
        // enable/order state into the Android Mods database before generating
        // requiredDataFiles.json. Only create build.ini from the database when
        // no manifest exists yet.
        val existingManifest = BuildManifest.read(ctx)
        val manifest = if (existingManifest != null) {
            BuildManifest.applyToDatabase(ctx)
            existingManifest
        } else {
            BuildManifest.writeFromDatabase(ctx)
        }
        if (manifest.content.isEmpty())
            throw IllegalStateException("No content files are selected")

        val dataFiles = File(GameInstaller.getDataFiles(ctx))
        if (!dataFiles.isDirectory)
            throw IllegalStateException("Data Files directory is not available: ${dataFiles.absolutePath}")

        val requiredEntries = manifest.content.map { entry(it, locate(dataFiles, it)) }
        val groundcoverEntries = manifest.groundcover.map { entry(it, locate(dataFiles, it)) }

        val output = File(ServerRuntime.serverHome(ctx), "data/requiredDataFiles.json")
        output.parentFile?.mkdirs()
        val json = buildString {
            append("{\n")
            append("  \"formatVersion\": 2,\n")
            append("  \"content\": [\n")
            requiredEntries.forEachIndexed { index, value ->
                append("    ").append(value)
                if (index + 1 < requiredEntries.size) append(',')
                append('\n')
            }
            append("  ],\n")
            append("  \"groundcover\": [\n")
            groundcoverEntries.forEachIndexed { index, value ->
                append("    ").append(value)
                if (index + 1 < groundcoverEntries.size) append(',')
                append('\n')
            }
            append("  ]\n")
            append("}\n")
        }
        val temp = File(output.parentFile, output.name + ".tmp")
        temp.writeText(json, Charsets.UTF_8)
        if (output.exists() && !output.delete())
            throw IllegalStateException("Could not replace ${output.absolutePath}")
        if (!temp.renameTo(output)) {
            output.writeText(json, Charsets.UTF_8)
            temp.delete()
        }
        return Result(requiredEntries.size, groundcoverEntries.size, output)
    }
}
