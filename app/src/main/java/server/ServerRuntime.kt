package server

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ServerRuntime {
    private const val ASSET_ROOT = "arenamp-server"
    private const val TAG = "ArenaMPServer"

    fun root(ctx: Context) = File(ctx.filesDir, "arenamp-server")
    fun serverHome(ctx: Context) = File(root(ctx), "server")
    fun userData(ctx: Context) = File(root(ctx), "userdata")
    fun userConfig(ctx: Context) = File(userData(ctx), "tes3mp-server.cfg")
    fun logFile(ctx: Context) = File(userData(ctx), "tes3mp-server.log")
    fun statusFile(ctx: Context) = File(userData(ctx), "android-server.status")
    fun backupDir(ctx: Context) = File(root(ctx), "Backup")

    private fun copyAssetTree(ctx: Context, assetPath: String, target: File, preserveServerData: Boolean) {
        val assets = ctx.assets
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            if (preserveServerData && target.exists()) return
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
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

    @Synchronized
    fun ensureInstalled(ctx: Context) {
        val runtime = root(ctx)
        runtime.mkdirs()
        userData(ctx).mkdirs()

        val packagedStamp = ctx.assets.open("$ASSET_ROOT/runtime-stamp.txt")
            .bufferedReader().use { it.readText().trim() }
        val installedStamp = File(runtime, ".runtime-stamp").takeIf { it.isFile }?.readText()?.trim().orEmpty()

        if (installedStamp != packagedStamp || !File(serverHome(ctx), "scripts/serverCore.lua").isFile) {
            Log.i(TAG, "Refreshing bundled server core: $installedStamp -> $packagedStamp")
            // Replace code/assets, but never overwrite existing server/data state.
            copyAssetTree(ctx, "$ASSET_ROOT/server", serverHome(ctx), false)
            copyAssetTree(ctx, "$ASSET_ROOT/resources", File(runtime, "resources"), false)
            copyAssetTree(ctx, "$ASSET_ROOT/tes3mp-server-default.cfg", File(runtime, "tes3mp-server-default.cfg"), false)
            File(runtime, ".runtime-stamp").writeText(packagedStamp)
        }

        val cfg = userConfig(ctx)
        if (!cfg.isFile) {
            copyAssetTree(ctx, "$ASSET_ROOT/tes3mp-server-default.cfg", cfg, false)
        }
        ServerConfig.ensurePluginHome(cfg)
    }

    fun writeStatus(ctx: Context, state: String, exitCode: Int? = null) {
        userData(ctx).mkdirs()
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
                val virtual = (iface.displayName + " " + iface.name).toLowerCase().let {
                    it.contains("virtual") || it.contains("docker") || it.contains("tun") || it.contains("vpn")
                }
                iface.inetAddresses.toList().forEach { address ->
                    if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) return@forEach
                    val value = address.hostAddress ?: return@forEach
                    var score = if (virtual) 0 else 100
                    if (value.startsWith("192.168.")) score += 30
                    else if (value.startsWith("10.")) score += 20
                    else if (value.startsWith("172.")) score += 10
                    candidates += score to value
                }
            }
            return candidates.maxBy { it.first }?.second ?: "127.0.0.1"
        } catch (_: Throwable) {
            return "127.0.0.1"
        }
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
}
