package file

import android.content.Context
import android.preference.PreferenceManager
import mods.ModType
import mods.ModsCollection
import mods.ModsDatabaseOpenHelper
import java.io.File

/** Portable ArenaMP build.ini compatible with the desktop ArenaMP launcher manifest. */
object BuildManifest {
    const val DEFAULT_SERVER_ADDRESS = "127.0.0.1"
    const val DEFAULT_SERVER_PORT = "25565"

    // Network identity of the parent PC ArenaMP checkpoint supplied with AMP(1).
    // This is intentionally independent from Android versionCode/versionName.
    const val DEFAULT_NETWORK_VERSION = "0.8.1"
    const val DEFAULT_NETWORK_PROTOCOL = "806"
    const val DEFAULT_NETWORK_COMMIT_HASH = "0f659371bcbaf9e7e6b94bd6bcb7a81970082234"
    private const val LEGACY_NETWORK_COMMIT_HASH_V126 = "ba8cf3b139c50b3f8e08069afee964294ad8fdbb"

    data class Data(
        var formatVersion: Int = 1,
        var name: String = "ArenaMP",
        var updateUrl: String = "",
        var dataPath: String = "",
        var language: String = "English",
        var complete: Boolean = false,
        var serverAddress: String = DEFAULT_SERVER_ADDRESS,
        var serverPort: String = DEFAULT_SERVER_PORT,
        var serverAddressSpecified: Boolean = false,
        var serverPortSpecified: Boolean = false,
        var vanillaServerCompatibility: Boolean = false,
        var networkVersion: String = DEFAULT_NETWORK_VERSION,
        var networkProtocol: String = DEFAULT_NETWORK_PROTOCOL,
        var networkCommitHash: String = DEFAULT_NETWORK_COMMIT_HASH,
        val content: MutableList<String> = mutableListOf(),
        val groundcover: MutableList<String> = mutableListOf(),
        val archives: MutableList<String> = mutableListOf()
    )

    private fun findCaseInsensitive(parent: File, name: String): File? {
        if (!parent.isDirectory) return null
        val wanted = name.toLowerCase()
        return parent.listFiles()?.firstOrNull { it.name.toLowerCase() == wanted }
    }

    private fun manifestFile(ctx: Context): File? {
        val rootPath = PreferenceManager.getDefaultSharedPreferences(ctx).getString("game_files", "") ?: ""
        if (rootPath.isBlank()) return null
        val root = File(rootPath)
        val data = File(GameInstaller(rootPath).findDataFiles())

        // Windows is case-insensitive, Android/Linux is not. A manifest copied
        // from PC as Build.ini / BUILD.INI must still be discovered here.
        findCaseInsensitive(root, "build.ini")?.let { return it }
        findCaseInsensitive(data, "build.ini")?.let { return it }

        // Keep a deterministic write destination for a new manifest.
        return File(root, "build.ini")
    }

    private fun unquote(v: String): String {
        val t = v.trim()
        if (t.length < 2 || t.first() != '"' || t.last() != '"') return t
        val src = t.substring(1, t.length - 1)
        val out = StringBuilder()
        var escaped = false
        src.forEach { ch ->
            if (escaped) {
                out.append(when (ch) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> ch })
                escaped = false
            } else if (ch == '\\') escaped = true else out.append(ch)
        }
        if (escaped) out.append('\\')
        return out.toString()
    }

    private fun quote(v: String): String {
        val out = StringBuilder("\"")
        v.forEach { ch ->
            when (ch) {
                '\\', '"' -> out.append('\\').append(ch)
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(ch)
            }
        }
        return out.append('"').toString()
    }

    private fun parseBool(v: String): Boolean {
        val s = v.trim().toLowerCase()
        return s == "true" || s == "1" || s == "yes"
    }

    private fun isBuildSection(section: String): Boolean =
        section.isEmpty() || section == "build" || section == "general" || section == "manifest"

    private fun isServerSection(section: String): Boolean =
        section.isEmpty() || section == "server" || section == "network" || section == "connection"

    private fun canonicalLanguage(v: String): String {
        return when (v.trim().toLowerCase()) {
            "", "en", "eng", "english", "английский", "anglais" -> "English"
            "french", "французский", "français" -> "French"
            "german", "немецкий", "deutsch" -> "German"
            "italian", "итальянский", "italiano" -> "Italian"
            "polish", "польский", "polski" -> "Polish"
            "ru", "rus", "russian", "русский", "русский язык" -> "Russian"
            "spanish", "испанский", "español" -> "Spanish"
            else -> v.trim()
        }
    }

    private fun languageCode(v: String): String =
        if (canonicalLanguage(v).equals("Russian", ignoreCase = true)) "RU" else "EN"

    fun read(ctx: Context): Data? {
        val f = manifestFile(ctx) ?: return null
        if (!f.exists()) return null
        val out = Data()
        var section = ""
        f.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEachLine
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().toLowerCase()
                return@forEachLine
            }
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEachLine
            val key = line.substring(0, eq).trim().toLowerCase()
            val value = unquote(line.substring(eq + 1))

            when {
                isBuildSection(section) && (key == "format" || key == "version") ->
                    value.toIntOrNull()?.takeIf { it > 0 }?.let { out.formatVersion = it }
                isBuildSection(section) && (key == "name" || key == "build-name") -> out.name = value
                isBuildSection(section) && (key == "update" || key == "update-url" || key == "update_url") -> out.updateUrl = value.trim()
                isBuildSection(section) && (key == "data" || key == "data-path" || key == "datafiles") -> out.dataPath = value
                key == "language" || key == "locale"
                    || ((section == "language" || section == "locale")
                        && (key == "value" || key == "name" || key == "selected" || key == "current")) ->
                    out.language = canonicalLanguage(value)
                isBuildSection(section) && (key == "complete" || key == "locked" || key == "read-only") ->
                    out.complete = parseBool(value)
                isServerSection(section) && (key == "address" || key == "ip" || key == "host") -> {
                    out.serverAddress = value.trim()
                    out.serverAddressSpecified = value.trim().isNotEmpty()
                }
                isServerSection(section) && key == "port" -> {
                    out.serverPort = value.trim()
                    out.serverPortSpecified = value.trim().isNotEmpty()
                }
                isServerSection(section) && (key == "vanilla-build-server" || key == "vanilla" || key == "legacy-client") ->
                    out.vanillaServerCompatibility = parseBool(value)
                isServerSection(section) && (key == "network-version" || key == "parent-version" || key == "tes3mp-version" || key == "version") ->
                    out.networkVersion = value.trim()
                isServerSection(section) && (key == "network-protocol" || key == "protocol" || key == "tes3mp-protocol") ->
                    out.networkProtocol = value.trim()
                isServerSection(section) && (key == "network-commit" || key == "network-commit-hash" || key == "parent-commit" || key == "commit-hash") ->
                    out.networkCommitHash = value.trim()
                key == "content" || key == "plugin" || key == "esm" || key == "esp"
                    || key == "omwgame" || key == "omwaddon" -> out.content.add(value)
                key == "groundcover" || key == "grass" -> out.groundcover.add(value)
                key == "archive" || key == "bsa" || key == "fallback-archive" -> out.archives.add(value)
            }
        }
        if (out.name.isBlank()) out.name = "ArenaMP"
        out.language = canonicalLanguage(out.language)
        if (out.serverAddress.isBlank()) out.serverAddress = DEFAULT_SERVER_ADDRESS
        if (out.serverPort.isBlank()) out.serverPort = DEFAULT_SERVER_PORT
        if (out.networkVersion.isBlank()) out.networkVersion = DEFAULT_NETWORK_VERSION
        if (out.networkProtocol.isBlank()) out.networkProtocol = DEFAULT_NETWORK_PROTOCOL
        if (out.networkCommitHash.isBlank()
            || out.networkCommitHash.equals(LEGACY_NETWORK_COMMIT_HASH_V126, ignoreCase = true)) {
            // V1.2.6/V1.2.7 wrote the newer Android build checkpoint into build.ini.
            // The parent PC server currently expects its resources/version identity instead.
            out.networkCommitHash = DEFAULT_NETWORK_COMMIT_HASH
        }
        return out
    }

    private fun collect(ctx: Context, existing: Data?): Data {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val dataFiles = GameInstaller.getDataFiles(ctx)
        val db = ModsDatabaseOpenHelper.getInstance(ctx)
        val mf = manifestFile(ctx)
        val dataDir = File(dataFiles)
        val defaultPortablePath = if (mf != null && mf.parentFile?.absolutePath == dataDir.absolutePath) "." else "Data Files"
        val encodingLanguage = if (prefs.getString("pref_encoding", "win1252") == "win1251") "Russian" else "English"
        val locked = existing?.complete ?: false

        val prefAddress = prefs.getString("pref_server_ip", DEFAULT_SERVER_ADDRESS)?.trim().orEmpty()
        val prefPort = prefs.getString("pref_server_port", DEFAULT_SERVER_PORT)?.trim().orEmpty()

        val out = Data(
            formatVersion = existing?.formatVersion?.takeIf { it > 0 } ?: 1,
            name = existing?.name?.takeIf { it.isNotBlank() } ?: "ArenaMP",
            updateUrl = existing?.updateUrl?.trim().orEmpty(),
            dataPath = existing?.dataPath?.takeIf { it.isNotBlank() } ?: defaultPortablePath,
            language = canonicalLanguage(existing?.language?.takeIf { it.isNotBlank() } ?: encodingLanguage),
            complete = locked,
            serverAddress = if (locked) existing?.serverAddress ?: DEFAULT_SERVER_ADDRESS else prefAddress.ifBlank { DEFAULT_SERVER_ADDRESS },
            serverPort = if (locked) existing?.serverPort ?: DEFAULT_SERVER_PORT else prefPort.ifBlank { DEFAULT_SERVER_PORT },
            serverAddressSpecified = if (locked) existing?.serverAddressSpecified ?: false else true,
            serverPortSpecified = if (locked) existing?.serverPortSpecified ?: false else true,
            vanillaServerCompatibility = existing?.vanillaServerCompatibility ?: false,
            networkVersion = existing?.networkVersion?.takeIf { it.isNotBlank() } ?: DEFAULT_NETWORK_VERSION,
            networkProtocol = existing?.networkProtocol?.takeIf { it.isNotBlank() } ?: DEFAULT_NETWORK_PROTOCOL,
            networkCommitHash = existing?.networkCommitHash?.takeIf { it.isNotBlank() } ?: DEFAULT_NETWORK_COMMIT_HASH
        )
        ModsCollection(ModType.Plugin, dataFiles, db).mods.filter { it.enabled }.sortedBy { it.order }
            .forEach { out.content.add(it.filename) }
        ModsCollection(ModType.Groundcover, dataFiles, db).mods.filter { it.enabled }.sortedBy { it.order }
            .forEach { out.groundcover.add(it.filename) }
        ModsCollection(ModType.Resource, dataFiles, db).mods.filter { it.enabled }.sortedBy { it.order }
            .forEach { out.archives.add(it.filename) }
        return out
    }

    private fun writeData(ctx: Context, out: Data): Data {
        val f = manifestFile(ctx) ?: return out
        f.parentFile?.mkdirs()
        val text = buildString {
            append("# ArenaMP portable build manifest\n")
            append("# Ordered entries are applied exactly as written.\n\n")
            append("[Build]\n")
            append("format=").append(if (out.formatVersion > 0) out.formatVersion else 1).append('\n')
            append("name=").append(quote(out.name.ifBlank { "ArenaMP" })).append('\n')
            if (out.updateUrl.isNotBlank()) append("update=").append(quote(out.updateUrl.trim())).append('\n')
            append("data-path=").append(quote(out.dataPath)).append('\n')
            append("language=").append(quote(languageCode(out.language))).append('\n')
            append("complete=").append(if (out.complete) "true" else "false").append("\n\n")
            append("[Server]\n")
            if (out.serverAddressSpecified) append("address=").append(quote(out.serverAddress)).append('\n')
            if (out.serverPortSpecified) append("port=").append(quote(out.serverPort)).append('\n')
            append("vanilla-build-server=").append(if (out.vanillaServerCompatibility) "true" else "false").append('\n')
            append("network-version=").append(quote(out.networkVersion)).append('\n')
            append("network-protocol=").append(quote(out.networkProtocol)).append('\n')
            append("network-commit=").append(quote(out.networkCommitHash)).append("\n\n")
            append("[Content]\n")
            out.content.forEach { append("content=").append(quote(it)).append('\n') }
            out.groundcover.forEach { append("groundcover=").append(quote(it)).append('\n') }
            append("\n[Archives]\n")
            out.archives.forEach { append("archive=").append(quote(it)).append('\n') }
        }
        f.writeText(text)
        return out
    }

    fun writeFromDatabase(ctx: Context): Data {
        val existing = read(ctx)
        val out = collect(ctx, existing)
        return writeData(ctx, out)
    }

    fun ensure(ctx: Context): Data = read(ctx) ?: writeFromDatabase(ctx)

    /**
     * Update only the launcher language scalar in build.ini. Do not regenerate
     * the manifest here: distributed builds may contain newer/custom keys that
     * this Android launcher does not know about yet.
     */
    fun syncLanguageAtStartup(ctx: Context, language: String): Data? {
        val f = manifestFile(ctx) ?: return null
        if (!f.isFile) return null
        val code = if (language.equals("RU", ignoreCase = true)) "RU" else "EN"
        val lines = f.readLines().toMutableList()
        var section = ""
        var buildHeader = -1
        var buildEnd = lines.size
        var replaced = false

        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val next = trimmed.substring(1, trimmed.length - 1).trim().toLowerCase()
                if (section == "build" && buildEnd == lines.size) buildEnd = i
                section = next
                if (section == "build" && buildHeader < 0) buildHeader = i
                continue
            }
            if (section == "build") {
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    val key = trimmed.substring(0, eq).trim().toLowerCase()
                    if (key == "language" || key == "locale") {
                        val indent = lines[i].takeWhile { it == ' ' || it == '\t' }
                        lines[i] = indent + key + "=" + quote(code)
                        replaced = true
                        break
                    }
                }
            }
        }

        if (!replaced) {
            if (buildHeader >= 0) {
                val at = buildEnd.coerceIn(buildHeader + 1, lines.size)
                lines.add(at, "language=" + quote(code))
            } else {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                lines.add("[Build]")
                lines.add("language=" + quote(code))
            }
        }

        f.writeText(lines.joinToString("\n") + "\n")
        return read(ctx)
    }

    /** Import desktop build.ini endpoint into Android preferences. */
    fun syncConnectionPreferences(ctx: Context): Data? {
        val m = read(ctx) ?: return null
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString("pref_server_ip", m.serverAddress.ifBlank { DEFAULT_SERVER_ADDRESS })
            .putString("pref_server_port", m.serverPort.ifBlank { DEFAULT_SERVER_PORT })
            .apply()
        return m
    }

    /**
     * Persist only the editable endpoint back to build.ini.
     *
     * IMPORTANT: build.ini is authoritative for content/load order. Updating an
     * IP/port preference must never rebuild the manifest from a possibly stale
     * ModsDatabase, otherwise merely opening/changing launcher settings can
     * silently replace the order supplied by the desktop build.ini.
     */
    fun updateConnectionFromPreferences(ctx: Context) {
        val existing = read(ctx)
        if (existing?.complete == true) {
            // complete=true locks only the distributed connection endpoint.
            // If an Android preference was changed programmatically, restore it
            // immediately instead of allowing UI state to drift from build.ini.
            syncConnectionPreferences(ctx)
            return
        }

        if (existing == null) {
            // No portable manifest exists yet: creating it from the current mod
            // database is the only sensible first-run behaviour.
            writeFromDatabase(ctx)
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        existing.serverAddress = prefs.getString("pref_server_ip", DEFAULT_SERVER_ADDRESS)
            ?.trim().orEmpty().ifBlank { DEFAULT_SERVER_ADDRESS }
        existing.serverPort = prefs.getString("pref_server_port", DEFAULT_SERVER_PORT)
            ?.trim().orEmpty().ifBlank { DEFAULT_SERVER_PORT }
        existing.serverAddressSpecified = true
        existing.serverPortSpecified = true
        writeData(ctx, existing)
    }

    private fun applySelectionToDatabase(
        ctx: Context,
        content: List<String>,
        groundcover: List<String>,
        archives: List<String>
    ) {
        val dataFiles = GameInstaller.getDataFiles(ctx)
        val db = ModsDatabaseOpenHelper.getInstance(ctx)
        fun apply(type: ModType, wanted: List<String>) {
            val c = ModsCollection(type, dataFiles, db)
            val order = wanted.mapIndexed { i, value -> value.toLowerCase() to i }.toMap()
            var tail = wanted.size
            c.mods.forEach { mod ->
                val pos = order[mod.filename.toLowerCase()]
                mod.enabled = pos != null
                mod.order = if (pos != null) pos + 1 else ++tail
                mod.dirty = true
            }
            c.mods.sortBy { it.order }
            c.update()
        }
        apply(ModType.Plugin, content)
        apply(ModType.Groundcover, groundcover)
        apply(ModType.Resource, archives)
    }

    fun applyToDatabase(ctx: Context) {
        val m = read(ctx) ?: return
        applySelectionToDatabase(ctx, m.content, m.groundcover, m.archives)
    }

    /**
     * Synchronize the mod database immediately after the user selects a game
     * folder. Existing build.ini is authoritative. If there is no build.ini yet,
     * bootstrap the initial plugin order/enabled state from Morrowind.ini and
     * create a portable manifest so subsequent launches are deterministic.
     */
    fun syncSelectedGame(ctx: Context, encoding: String): Data? {
        val existing = read(ctx)
        if (existing != null) {
            applySelectionToDatabase(ctx, existing.content, existing.groundcover, existing.archives)
            syncConnectionPreferences(ctx)
            return existing
        }

        val rootPath = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString("game_files", "").orEmpty()
        if (rootPath.isBlank()) return null

        val installer = GameInstaller(rootPath)
        val selection = installer.readDataFilesSelection(encoding)
        val dataFiles = installer.findDataFiles()

        // Morrowind.ini may omit Morrowind.bsa because the original engine loads
        // it implicitly. Keep installed vanilla archives enabled and add any
        // explicitly registered archives from the INI in their original order.
        val vanillaArchives = listOf("Morrowind.bsa", "Tribunal.bsa", "Bloodmoon.bsa")
            .filter { File(dataFiles, it).exists() }
        val iniArchives = selection?.archives ?: emptyList()
        val archives = (vanillaArchives + iniArchives).fold(arrayListOf<String>()) { acc, value ->
            if (acc.none { it.equals(value, ignoreCase = true) }) acc.add(value)
            acc
        }

        val installedVanillaPlugins = listOf("Morrowind.esm", "Tribunal.esm", "Bloodmoon.esm")
            .filter { File(dataFiles, it).exists() }
        val content = selection?.content?.takeIf { it.isNotEmpty() } ?: installedVanillaPlugins

        applySelectionToDatabase(ctx, content, emptyList(), archives)
        return writeFromDatabase(ctx)
    }
}
