package server

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writable portable ArenaMP dedicated-server runtime. */
object ServerRuntime {
    private const val ASSET_ROOT = "arenamp-server"
    private const val TAG = "ArenaMPServer"
    private const val PUBLIC_ROOT_NAME = "ArenaMP"
    private val REQUIRED_DATA_DIRS = arrayOf("player", "cell", "world", "map", "custom", "recordstore")

    private fun probeWritable(directory: File): Boolean {
        return try {
            if (!(directory.isDirectory || directory.mkdirs())) return false
            val probe = File(directory, ".arenamp-write-probe-${android.os.Process.myPid()}")
            probe.writeText("ok\n", Charsets.UTF_8)
            val ok = probe.isFile && probe.length() > 0L
            probe.delete()
            ok
        } catch (_: Throwable) {
            false
        }
    }

    fun root(ctx: Context): File {
        // Prefer the desktop-like /storage/emulated/0/ArenaMP directory, but
        // canWrite() alone is not a reliable scoped-storage test. Perform an
        // actual create/write/delete probe before giving the native server the path.
        val publicRoot = File(Environment.getExternalStorageDirectory(), PUBLIC_ROOT_NAME)
        if (probeWritable(publicRoot)) return publicRoot

        // Guaranteed writable external application storage fallback. This is
        // still on shared/external storage rather than /data/user/0.
        val fallback = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, PUBLIC_ROOT_NAME)
        if (probeWritable(fallback)) return fallback
        throw IllegalStateException("No writable ArenaMP server storage is available")
    }

    fun serverHome(ctx: Context) = File(root(ctx), "server")
    fun configDir(ctx: Context) = File(root(ctx), "config")
    fun userConfig(ctx: Context) = File(configDir(ctx), "tes3mp-server.cfg")
    fun persistentScriptConfig(ctx: Context) = File(configDir(ctx), "server-config.lua")
    fun runtimeScriptConfig(ctx: Context) = File(serverHome(ctx), "scripts/config.lua")
    fun logFile(ctx: Context) = File(configDir(ctx), "tes3mp-server.log")
    fun statusFile(ctx: Context) = File(configDir(ctx), "android-server.status")
    fun runtimeStamp(ctx: Context) = File(configDir(ctx), ".server-runtime-stamp")
    private fun legacyMigrationMarker(ctx: Context) = File(configDir(ctx), ".legacy-private-runtime-migrated")
    fun backupDir(ctx: Context) = File(root(ctx), "Backup")

    private fun copyAssetTree(ctx: Context, assetPath: String, target: File, preserveServerData: Boolean) {
        val children = ctx.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            if (preserveServerData && target.exists()) return
            target.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { name ->
            val childAsset = "$assetPath/$name"
            val childTarget = File(target, name)
            val preserve = preserveServerData || childAsset.startsWith("$ASSET_ROOT/server/data/")
            copyAssetTree(ctx, childAsset, childTarget, preserve)
        }
    }

    private fun copyMissingTree(source: File, target: File) {
        if (!source.exists()) return
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { copyMissingTree(it, File(target, it.name)) }
        } else if (!target.exists()) {
            target.parentFile?.mkdirs()
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }

    /** Migrate the V1.0-V1.3 private runtime once without deleting the old copy. */
    private fun migrateLegacyPrivateRuntime(ctx: Context) {
        val marker = legacyMigrationMarker(ctx)
        if (marker.isFile) return
        val old = File(ctx.filesDir, "arenamp-server")
        if (!old.isDirectory) return
        val newRoot = root(ctx)
        if (old.absolutePath == newRoot.absolutePath) return
        copyMissingTree(File(old, "server/data"), File(newRoot, "server/data"))
        copyMissingTree(File(old, "userdata/tes3mp-server.cfg"), userConfig(ctx))
        copyMissingTree(File(old, "userdata/server-config.lua"), persistentScriptConfig(ctx))
        copyMissingTree(File(old, "userdata/tes3mp-server.log"), logFile(ctx))
        marker.parentFile?.mkdirs()
        marker.writeText("migrated\n")
    }

    /**
     * CoreScripts expect require("cjson"), but the portable PC package only
     * carries cjson.dll. Android installs an API-compatible module backed by
     * bundled dkjson, so JSON I/O stays self-contained and the missing-CJSON
     * error disappears without rebuilding the native dependency checkpoint.
     */
    private fun ensureCjsonCompatibilityModule(ctx: Context) {
        val file = File(serverHome(ctx), "lib/lua/cjson.lua")
        if (File(serverHome(ctx), "lib/cjson.so").isFile || file.isFile) return
        file.parentFile?.mkdirs()
        file.writeText(CJSON_COMPAT, Charsets.UTF_8)
    }

    @Synchronized
    fun ensureInstalled(ctx: Context) {
        val runtime = root(ctx)
        runtime.mkdirs()
        configDir(ctx).mkdirs()

        // CoreScripts expect these default folders to exist before the first
        // player/cell/world JSON is written. Android assets do not preserve
        // empty directories, so create them explicitly on every install/start.
        ensureDefaultDataDirectories(ctx)
        migrateLegacyPrivateRuntime(ctx)

        val packagedStamp = ctx.assets.open("$ASSET_ROOT/runtime-stamp.txt")
            .bufferedReader().use { it.readText().trim() }
        val installedStamp = runtimeStamp(ctx).takeIf { it.isFile }?.readText()?.trim().orEmpty()

        if (installedStamp != packagedStamp || !File(serverHome(ctx), "scripts/serverCore.lua").isFile) {
            Log.i(TAG, "Refreshing portable server core: $installedStamp -> $packagedStamp at ${runtime.absolutePath}")
            copyAssetTree(ctx, "$ASSET_ROOT/server", serverHome(ctx), false)
            copyAssetTree(ctx, "$ASSET_ROOT/resources", File(runtime, "resources"), false)
            copyAssetTree(ctx, "$ASSET_ROOT/tes3mp-server-default.cfg", File(runtime, "tes3mp-server-default.cfg"), false)
            runtimeStamp(ctx).writeText(packagedStamp)
        }

        val cfg = userConfig(ctx)
        if (!cfg.isFile)
            copyAssetTree(ctx, "$ASSET_ROOT/tes3mp-server-default.cfg", cfg, false)
        ServerConfig.ensurePluginHome(cfg)

        // Same idea as the PC launcher: config/server-config.lua is the
        // persistent authoritative copy; server/scripts/config.lua is runtime.
        val persistent = persistentScriptConfig(ctx)
        val runtimeConfig = runtimeScriptConfig(ctx)
        if (!persistent.isFile && runtimeConfig.isFile) {
            persistent.parentFile?.mkdirs()
            runtimeConfig.copyTo(persistent, overwrite = false)
        } else if (persistent.isFile) {
            runtimeConfig.parentFile?.mkdirs()
            persistent.copyTo(runtimeConfig, overwrite = true)
        }

        ensureCjsonCompatibilityModule(ctx)
        ensureDefaultDataDirectories(ctx)
        ensureWritableDataTree(ctx)
    }

    private fun ensureDefaultDataDirectories(ctx: Context) {
        val data = File(serverHome(ctx), "data")
        if (!(data.isDirectory || data.mkdirs()))
            throw IllegalStateException("Could not create server data directory: ${data.absolutePath}")
        REQUIRED_DATA_DIRS.forEach { name ->
            val dir = File(data, name)
            if (!(dir.isDirectory || dir.mkdirs()))
                throw IllegalStateException("Could not create default server data directory: ${dir.absolutePath}")
        }
    }

    /**
     * Android's asset packaging does not preserve empty directories reliably.
     * CoreScripts write directly to data/player, data/cell, etc., so create
     * every writable directory explicitly instead of relying on .gitkeep.
     */
    private fun ensureWritableDataTree(ctx: Context) {
        val data = File(serverHome(ctx), "data")
        if (!probeWritable(data))
            throw IllegalStateException("Server data directory is not writable: ${data.absolutePath}")
        REQUIRED_DATA_DIRS.forEach { name ->
            val dir = File(data, name)
            if (!probeWritable(dir))
                throw IllegalStateException("Server data directory is not writable: ${dir.absolutePath}")
        }
        if (!probeWritable(configDir(ctx)))
            throw IllegalStateException("Server config directory is not writable: ${configDir(ctx).absolutePath}")
    }

    fun verifyWritableRuntime(ctx: Context): String {
        ensureInstalled(ctx)
        val checks = arrayOf(
            root(ctx), configDir(ctx), serverHome(ctx), File(serverHome(ctx), "data"),
            File(serverHome(ctx), "data/player"), File(serverHome(ctx), "data/cell"),
            File(serverHome(ctx), "data/world"), File(serverHome(ctx), "data/map"),
            File(serverHome(ctx), "data/custom"), File(serverHome(ctx), "data/recordstore")
        )
        checks.forEach { dir ->
            if (!probeWritable(dir))
                throw IllegalStateException("ArenaMP server cannot write to ${dir.absolutePath}")
        }
        return root(ctx).absolutePath
    }

    fun syncPersistentScriptConfig(ctx: Context) {
        ensureInstalled(ctx)
        val persistent = persistentScriptConfig(ctx)
        val runtime = runtimeScriptConfig(ctx)
        if (persistent.isFile) persistent.copyTo(runtime, overwrite = true)
    }

    fun writeStatus(ctx: Context, state: String, exitCode: Int? = null) {
        configDir(ctx).mkdirs()
        statusFile(ctx).writeText(buildString {
            append("state=").append(state).append('\n')
            append("pid=").append(android.os.Process.myPid()).append('\n')
            if (exitCode != null) append("exitCode=").append(exitCode).append('\n')
            append("time=").append(System.currentTimeMillis()).append('\n')
        })
    }

    fun readStatus(ctx: Context): String {
        val file = statusFile(ctx)
        if (!file.isFile) return "stopped"
        return file.readLines().firstOrNull { it.startsWith("state=") }?.substringAfter('=') ?: "stopped"
    }

    fun lanAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidates = mutableListOf<Pair<Int, String>>()
            interfaces.forEach { iface ->
                if (!iface.isUp || iface.isLoopback) return@forEach
                val tag = (iface.displayName + " " + iface.name).toLowerCase()
                iface.inetAddresses.toList().forEach { address ->
                    if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) return@forEach
                    val value = address.hostAddress ?: return@forEach

                    // Prefer addresses another player can realistically use:
                    // Wi-Fi/Ethernet first, then overlay/tunnel VPN, and only
                    // then carrier/mobile interfaces. The previous code heavily
                    // penalised tun/vpn, so on mobile data it advertised a CGNAT
                    // 10.x carrier address even when a usable mesh-VPN address existed.
                    var score = when {
                        tag.contains("wlan") || tag.contains("wifi") -> 400
                        tag.contains("eth") -> 380
                        tag.contains("tun") || tag.contains("tap") || tag.contains("vpn") -> 330
                        tag.contains("rmnet") || tag.contains("ccmni") || tag.contains("wwan") ||
                            tag.contains("pdp") || tag.contains("cell") -> 100
                        tag.contains("docker") || tag.contains("virtual") -> 20
                        else -> 220
                    }
                    if (value.startsWith("192.168.")) score += 30
                    else if (value.startsWith("100.")) score += 15
                    else if (value.startsWith("10.")) score += 10
                    else if (value.startsWith("172.")) score += 5
                    candidates += score to value
                }
            }
            return candidates.maxBy { it.first }?.second ?: "127.0.0.1"
        } catch (_: Throwable) {
            return "127.0.0.1"
        }
    }

    private fun clearDirectoryContents(directory: File): Boolean {
        if (!directory.exists()) return true
        var ok = true
        directory.listFiles()?.forEach { if (!it.deleteRecursively()) ok = false }
        directory.mkdirs()
        return ok
    }

    fun clearPersistentCells(ctx: Context): Boolean {
        if (readStatus(ctx) == "running") return false
        return clearDirectoryContents(File(serverHome(ctx), "data/cell"))
    }

    fun resetPersistentServerData(ctx: Context): Boolean {
        if (readStatus(ctx) == "running") return false
        val data = File(serverHome(ctx), "data")
        val gameplayDirs = arrayOf("player", "cell", "world", "map", "custom", "recordstore")
        var ok = true
        gameplayDirs.forEach { if (!clearDirectoryContents(File(data, it))) ok = false }
        val database = File(data, "database.db")
        if (database.exists() && !database.delete()) ok = false
        // requiredDataFiles.json and banlist.json are intentionally preserved.
        return ok
    }

    fun createBackup(ctx: Context): File? {
        val source = serverHome(ctx)
        if (!source.isDirectory) return null
        val dir = backupDir(ctx).apply { mkdirs() }
        val out = File(dir, "archive_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(source.parentFile!!).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(rel))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    private val CJSON_COMPAT = """
-- ArenaMP Android cjson compatibility module.
-- It intentionally uses the bundled dkjson implementation so the dedicated
-- server does not depend on a loadable cjson.so in external storage.
local dkjson = require("dkjson")
local cjson = {}
local empty_table_as_object = false

function cjson.encode_sparse_array(...) return true end
function cjson.encode_invalid_numbers(...) return true end
function cjson.decode_null_as_lightuserdata(...) return true end
function cjson.encode_empty_table_as_object(value)
    if value ~= nil then empty_table_as_object = not not value end
    return empty_table_as_object
end

local array_mt = { __jsontype = "array" }
local function prepare(value, seen)
    if type(value) == "number" and (value ~= value or value == math.huge or value == -math.huge) then
        return dkjson.null
    end
    if type(value) ~= "table" then return value end
    seen = seen or {}
    if seen[value] then return seen[value] end
    local out = {}
    seen[value] = out
    local empty = true
    for k, v in pairs(value) do
        empty = false
        out[prepare(k, seen)] = prepare(v, seen)
    end
    if empty and not empty_table_as_object then setmetatable(out, array_mt) end
    return out
end

function cjson.encode(value)
    local ok, encoded = pcall(dkjson.encode, prepare(value))
    if not ok then error(encoded) end
    return encoded
end

function cjson.decode(text)
    local value, _, err = dkjson.decode(text, 1, nil)
    if err then error(err) end
    return value
end

return cjson
""".trimIndent() + "\n"
}
