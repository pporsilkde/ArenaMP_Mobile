package server

import android.content.Context
import java.io.File

object ServerScriptConfig {
    enum class Mode { COOP, MMO }

    private val sharedKeys = arrayOf(
        "shareJournal", "shareFactionRanks", "shareFactionExpulsion",
        "shareFactionReputation", "shareTopics", "shareReputation",
        "shareMapExploration", "shareVideos", "shareKills"
    )

    private fun replaceAssignment(text: String, key: String, value: String): String {
        val regex = Regex("(?m)^(\\s*config\\.${Regex.escape(key)}\\s*=\\s*).*$")
        val match = regex.find(text)
        if (match != null)
            return text.replaceRange(match.range, match.groupValues[1] + value)
        val returnRegex = Regex("(?m)^\\s*return\\s+config\\s*$")
        return if (returnRegex.containsMatchIn(text))
            returnRegex.replaceFirst(text, "config.$key = $value\n\nreturn config")
        else text.trimEnd() + "\nconfig.$key = $value\n"
    }

    fun detect(ctx: Context): Mode {
        ServerRuntime.ensureInstalled(ctx)
        val file = ServerRuntime.persistentScriptConfig(ctx)
        if (!file.isFile) return Mode.COOP
        val text = file.readText(Charsets.UTF_8)
        val mode = Regex("(?m)^\\s*config\\.gameMode\\s*=\\s*[\"']([^\"']+)")
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
        if (mode.contains("MMO", ignoreCase = true)) return Mode.MMO
        val journal = Regex("(?m)^\\s*config\\.shareJournal\\s*=\\s*(true|false)")
            .find(text)?.groupValues?.getOrNull(1)
        return if (journal == "false") Mode.MMO else Mode.COOP
    }

    fun enforceRequiredDataFiles(ctx: Context): Boolean {
        ServerRuntime.ensureInstalled(ctx)
        val file = ServerRuntime.persistentScriptConfig(ctx)
        if (!file.isFile) return false
        val text = file.readText(Charsets.UTF_8)
        return Regex("(?m)^\\s*config\\.enforceDataFiles\\s*=\\s*(true|false)")
            .find(text)?.groupValues?.getOrNull(1) == "true"
    }

    fun setEnforceRequiredDataFiles(ctx: Context, enabled: Boolean): File {
        ServerRuntime.ensureInstalled(ctx)
        val file = ServerRuntime.persistentScriptConfig(ctx)
        var text = if (file.isFile) file.readText(Charsets.UTF_8)
        else ServerRuntime.runtimeScriptConfig(ctx).readText(Charsets.UTF_8)
        text = replaceAssignment(text, "enforceDataFiles", if (enabled) "true" else "false")
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        ServerRuntime.syncPersistentScriptConfig(ctx)
        return file
    }

    fun apply(ctx: Context, mode: Mode): File {
        ServerRuntime.ensureInstalled(ctx)
        val file = ServerRuntime.persistentScriptConfig(ctx)
        var text = if (file.isFile) file.readText(Charsets.UTF_8)
        else ServerRuntime.runtimeScriptConfig(ctx).readText(Charsets.UTF_8)
        val coop = mode == Mode.COOP
        text = replaceAssignment(text, "gameMode", if (coop) "\"ArenaMP CO-OP\"" else "\"ArenaMP MMO\"")
        sharedKeys.forEach { text = replaceAssignment(text, it, if (coop) "true" else "false") }
        text = replaceAssignment(text, "shareBounty", "false")
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        ServerRuntime.syncPersistentScriptConfig(ctx)
        return file
    }
}
