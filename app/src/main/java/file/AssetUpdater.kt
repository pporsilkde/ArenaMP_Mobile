package file

import android.content.Context
import com.libopenmw.openmw.BuildConfig
import constants.Constants
import file.utils.CopyFilesFromAssets
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Deployment identity is computed from the actual APK assets, independently
 * of the TES3MP protocol/resources/version identity and build.ini revisions. */
object AssetUpdater {
    private data class CachedFingerprint(val apkIdentity: String, val value: String)
    private val fingerprints = HashMap<String, CachedFingerprint>()

    /**
     * PackageInstaller can return to an already running launcher process after
     * replacing the APK. Key the cache by the actual installed package identity,
     * otherwise client/server assets from the previous APK can be mistaken for
     * the freshly installed ones until the process is restarted.
     */
    private fun apkIdentity(ctx: Context): String {
        val source = File(ctx.applicationInfo.sourceDir)
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return info.lastUpdateTime.toString() + ":" + source.length() + ":" + source.lastModified()
    }

    @Synchronized
    fun invalidateAll() {
        fingerprints.clear()
    }

    @Synchronized
    fun invalidate(prefix: String) {
        fingerprints.remove(prefix)
    }

    @Synchronized
    fun fingerprint(ctx: Context, prefix: String): String {
        val identity = apkIdentity(ctx)
        fingerprints[prefix]?.takeIf { it.apkIdentity == identity }?.let { return it.value }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        ZipFile(ctx.applicationInfo.sourceDir).use { apk ->
            val entries = apk.entries().toList().filter { !it.isDirectory && it.name.startsWith("assets/$prefix/") }.sortedBy { it.name }
            for (entry in entries) {
                digest.update((entry.name + "\u0000" + entry.crc + ":" + entry.size + "\n").toByteArray(Charsets.UTF_8))
                count++
            }
        }
        check(count > 0) { "APK assets are missing: $prefix" }
        val result = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        fingerprints[prefix] = CachedFingerprint(identity, result)
        return result
    }

    fun clientIsCurrent(ctx: Context): Boolean {
        val marker = File(ctx.filesDir, "client-assets.sha256")
        return marker.isFile && marker.readText().trim() == fingerprint(ctx, "libopenmw")
            && File(Constants.RESOURCES, "version").isFile && File(Constants.DEFAULTS_BIN).isFile
    }

    @Synchronized
    fun ensureClientInstalled(ctx: Context) {
        UpdateLog.write(ctx, "assets_check", "apk=${ctx.applicationInfo.sourceDir}")
        val root = ctx.filesDir.canonicalFile
        AssetTransaction.recover(root)
        val expected = fingerprint(ctx, "libopenmw")
        val marker = File(root, "client-assets.sha256")
        val ready = marker.isFile && marker.readText().trim() == expected
            && File(Constants.RESOURCES, "version").isFile
            && File(Constants.DEFAULTS_BIN).isFile
        if (!ready) {
            UpdateLog.write(ctx, "assets_install", "old=${if (marker.isFile) marker.readText().trim() else "missing"} expected=$expected")
            val stage = File(root, ".arena-client-assets-stage")
            ContentUpdate.deleteTree(stage)
            check(stage.mkdirs()) { "Cannot create asset staging directory" }
            try {
                val copier = CopyFilesFromAssets(ctx)
                copier.copy("libopenmw/resources", File(stage, "resources").absolutePath)
                copier.copy("libopenmw/openmw", File(stage, "config").absolutePath)
                check(File(stage, "resources/version").isFile) { "APK has no resources/version" }
                check(File(stage, "config/defaults.bin").isFile) { "APK has no defaults.bin" }
                AssetTransaction.apply(root, stage, listOf("resources", "config"), "client-assets.sha256", expected)
                UpdateLog.write(ctx, "assets_installed", "fingerprint=$expected; resources and config replaced")
            } finally { ContentUpdate.deleteTree(stage) }
        } else UpdateLog.write(ctx, "assets_current", "fingerprint=$expected")
        File(Constants.USER_CONFIG).mkdirs()
        val userConfig = File(Constants.USER_OPENMW_CFG)
        if (!userConfig.exists()) userConfig.writeText("# User openmw.cfg\n")
        File(Constants.VERSION_STAMP).writeText(BuildConfig.VERSION_CODE.toString())
    }
}
