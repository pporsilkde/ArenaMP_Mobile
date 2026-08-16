package server

import java.io.File

data class ServerConfigData(
    var localAddress: String = "0.0.0.0",
    var port: String = "25565",
    var maximumPlayers: String = "100",
    var hostname: String = "ArenaMP server",
    var password: String = "",
    var logLevel: String = "1"
)

object ServerConfig {
    fun load(file: File): ServerConfigData {
        val out = ServerConfigData()
        if (!file.isFile) return out
        var section = ""
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim()
                return@forEachLine
            }
            if (section != "General" || line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEachLine
            val eq = line.indexOf('=')
            if (eq < 0) return@forEachLine
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when (key) {
                "localAddress" -> out.localAddress = value
                "port" -> out.port = value
                "maximumPlayers" -> out.maximumPlayers = value
                "hostname" -> out.hostname = value
                "password" -> out.password = value
                "logLevel" -> out.logLevel = value
            }
        }
        return out
    }

    private fun updateIni(file: File, section: String, values: Map<String, String>) {
        val input = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
        var sectionStart = -1
        var sectionEnd = input.size
        for (i in input.indices) {
            val t = input[i].trim()
            if (t.equals("[$section]", ignoreCase = true)) {
                sectionStart = i
                for (j in i + 1 until input.size) {
                    val n = input[j].trim()
                    if (n.startsWith("[") && n.endsWith("]")) { sectionEnd = j; break }
                }
                break
            }
        }
        if (sectionStart < 0) {
            if (input.isNotEmpty() && input.last().isNotBlank()) input.add("")
            sectionStart = input.size
            input.add("[$section]")
            sectionEnd = input.size
        }
        val remaining = values.toMutableMap()
        for (i in sectionStart + 1 until sectionEnd) {
            val line = input[i]
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim()
            val value = remaining.remove(key) ?: continue
            input[i] = "$key = $value"
        }
        var insertAt = sectionEnd
        remaining.forEach { (key, value) ->
            input.add(insertAt++, "$key = $value")
        }
        file.parentFile?.mkdirs()
        file.writeText(input.joinToString("\n", postfix = "\n"))
    }

    fun save(file: File, cfg: ServerConfigData) {
        val port = cfg.port.toIntOrNull()?.coerceIn(1, 65535) ?: 25565
        val players = cfg.maximumPlayers.toIntOrNull()?.coerceIn(1, 1000) ?: 100
        updateIni(file, "General", linkedMapOf(
            "localAddress" to cfg.localAddress.ifBlank { "0.0.0.0" },
            "port" to port.toString(),
            "maximumPlayers" to players.toString(),
            "hostname" to cfg.hostname.ifBlank { "ArenaMP server" },
            "logLevel" to cfg.logLevel.toIntOrNull()?.coerceIn(0, 4)?.toString().orEmpty().ifBlank { "1" },
            "password" to cfg.password
        ))
        ensurePluginHome(file)
    }

    fun setPort(file: File, port: String) {
        val parsed = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 25565
        updateIni(file, "General", mapOf("port" to parsed.toString()))
    }

    fun ensurePluginHome(file: File) {
        updateIni(file, "Plugins", mapOf("home" to "./server", "plugins" to "serverCore.lua"))
    }
}
