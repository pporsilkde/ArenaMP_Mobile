package file

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.preference.PreferenceManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.libopenmw.openmw.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Launcher-only flow: game/server start must happen only from onReady. */
@Suppress("DEPRECATION")
object LauncherUpdater {
    const val INSTALL_PERMISSION = 7401
    const val INSTALL_APK = 7402
    private const val TAG = "ArenaUpdater"
    @Volatile private var busy = false

    private fun state(activity: Activity) = activity.getSharedPreferences("arena_updater", 0)
    private fun apk(activity: Activity) = File(activity.filesDir, "updates/engine.apk")

    /** A downloaded APK is not an installed engine. Acknowledge only the observed package update. */
    fun reconcileInstalledApk(activity: Activity) {
        val pending = state(activity)
        val expected = pending.getLong("apk_version", -1)
        if (expected < 0) return
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val version = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        if (version < expected || installed.lastUpdateTime <= pending.getLong("before_update", Long.MAX_VALUE)) return
        val manifestFile = BuildManifest.manifestFile(activity) ?: return
        if (manifestFile.canonicalPath != pending.getString("manifest", "")) return
        if (!AssetUpdater.clientIsCurrent(activity)) return
        val m = BuildManifest.read(activity) ?: return
        val revision = pending.getString("build", "").orEmpty()
        if (ContentUpdate.revision(revision) > ContentUpdate.revision(m.engineBuild)) {
            m.engineBuild = revision
            BuildManifest.writeData(activity, m)
        }
        pending.edit().clear().commit()
        apk(activity).delete()
    }

    fun beforeLaunch(activity: Activity, onReady: () -> Unit) {
        if (busy || activity.isFinishing) return
        val manifest = BuildManifest.read(activity)
        if (manifest == null) { onReady(); return }
        val manifestFile = BuildManifest.manifestFile(activity) ?: run { onReady(); return }
        val data = File(GameInstaller.getDataFiles(activity)).canonicalFile
        val journal = File(manifestFile.parentFile, ".arena-android-update.properties")
        busy = true
        val cancel = AtomicBoolean(false)
        val dialog = ProgressDialog(activity).apply {
            setTitle(R.string.arena_update_title)
            setMessage(activity.getString(R.string.arena_update_check))
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = true
            setCancelable(true)
            setCanceledOnTouchOutside(false)
            setOnCancelListener { cancel.set(true) }
            show()
        }
        fun ui(action: () -> Unit) { activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) action() } }
        fun finish(action: () -> Unit) { ui { busy = false; dialog.dismiss(); action() } }
        val progress = ContentUpdate.Progress { done, total ->
            ui { if (total > 0) { dialog.isIndeterminate = false; dialog.progress = (done * 100 / total).toInt() } }
        }
        Thread {
            var stage: File? = null
            val checkFile = File(activity.cacheDir, "arena-check.ini")
            val zip = File(activity.cacheDir, "arena-content.zip")
            try {
                // Recovery happens even when check.ini is absent/unreachable.
                ContentUpdate.recover(journal)
                ui { dialog.setMessage(activity.getString(R.string.arena_update_assets)) }
                AssetUpdater.ensureClientInstalled(activity)
                ui { dialog.setMessage(activity.getString(R.string.arena_update_check)) }
                reconcileInstalledApk(activity)
                val current = BuildManifest.read(activity) ?: throw IllegalStateException("build.ini is missing")
                if (current.checkUrl.isBlank()) {
                    finish { if (!cancel.get()) onReady() }; return@Thread
                }
                val remote: Map<String, String>
                try {
                    ContentUpdate.download(current.checkUrl, checkFile, true, "", cancel, ContentUpdate.Progress { _, _ -> })
                    remote = ContentUpdate.check(ContentUpdate.readText(checkFile))
                    ContentUpdate.revision(current.contentVersion)
                    ContentUpdate.revision(current.engineBuild)
                } catch (e: Exception) {
                    Log.w(TAG, "check.ini unavailable; continuing with installed build", e)
                    finish { if (!cancel.get()) onReady() }; return@Thread
                }
                if (cancel.get()) { finish {}; return@Thread }
                val contentNeeded = ContentUpdate.revision(remote["version"]) > ContentUpdate.revision(current.contentVersion)
                val engineNeeded = ContentUpdate.revision(remote["build"]) > ContentUpdate.revision(current.engineBuild)
                // APK is fully downloaded and validated before applying content, so an invalid
                // engine download does not leave a partially updated content/engine combination.
                var downloadedApk: File? = null
                if (engineNeeded) {
                    ui { dialog.setMessage(activity.getString(R.string.arena_update_engine)); dialog.isIndeterminate = true }
                    val finalApk = apk(activity)
                    finalApk.parentFile?.mkdirs()
                    val pending = state(activity)
                    val reuse = finalApk.isFile && pending.getString("build", "") == remote["build"]
                        && pending.getString("manifest", "") == manifestFile.canonicalPath
                        && pending.getString("url", "") == current.androidUrl
                    if (!reuse) {
                        val part = File(finalApk.parentFile, "engine.apk.part")
                        try {
                            ContentUpdate.download(current.androidUrl, part, false, remote["sha256_android"].orEmpty(), cancel, progress)
                            validateApk(activity, part)
                            if (finalApk.exists() && !finalApk.delete()) throw IllegalStateException("Cannot replace cached APK")
                            if (!part.renameTo(finalApk)) throw IllegalStateException("Cannot save downloaded APK")
                        } finally { part.delete() }
                    }
                    val info = validateApk(activity, finalApk)
                    val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
                    val apkVersion = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                    if (!pending.edit().putString("build", remote["build"])
                            .putString("manifest", manifestFile.canonicalPath)
                            .putString("url", current.androidUrl)
                            .putLong("apk_version", apkVersion)
                            .putLong("before_update", installed.lastUpdateTime).commit())
                        throw IllegalStateException("Cannot save APK installation state")
                    downloadedApk = finalApk
                }
                if (contentNeeded) {
                    ui { dialog.setMessage(activity.getString(R.string.arena_update_content)); dialog.isIndeterminate = true }
                    ContentUpdate.download(current.updateUrl, zip, false, remote["sha256_update"].orEmpty(), cancel, progress)
                    stage = File(data.parentFile, ".arena-android-stage-" + System.currentTimeMillis())
                    ui { dialog.setMessage(activity.getString(R.string.arena_update_extract)); dialog.isIndeterminate = true }
                    val payload = ContentUpdate.extract(zip, stage, cancel)
                    if (cancel.get()) { finish {}; return@Thread }
                    // Cancellation is allowed during download/staging; committing must finish or roll back.
                    ui { dialog.setCancelable(false) }
                    ContentUpdate.install(payload, data, manifestFile, remote["version"]!!, journal)
                }
                finish {
                    if (downloadedApk != null) installApk(activity)
                    else if (!cancel.get()) onReady()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update failed", e)
                finish {
                    if (!cancel.get()) AlertDialog.Builder(activity).setTitle(R.string.update_failed_title)
                        .setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            } finally {
                checkFile.delete(); zip.delete()
                try { stage?.let { ContentUpdate.deleteTree(it) } } catch (_: Exception) {}
                // onReady is never called from a destroyed activity.
                if (activity.isFinishing || activity.isDestroyed) busy = false
            }
        }.start()
    }

    private fun validateApk(activity: Activity, file: File): android.content.pm.PackageInfo {
        val pm = activity.packageManager
        val info = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
            ?: throw IllegalArgumentException("Downloaded file is not an APK")
        val installed = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
        require(info.packageName == activity.packageName) { "APK package/applicationId does not match this launcher" }
        val signatures = info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        require(signatures.isNotEmpty() && signatures == installed.signatures?.map { it.toCharsString() }?.toSet()) {
            "APK must be signed with the same release key as the installed launcher"
        }
        val newVersion = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val oldVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(newVersion > oldVersion) { "APK versionCode must be higher than the installed version" }
        return info
    }

    private fun installApk(activity: Activity) {
        try {
            val file = apk(activity)
            if (!file.isFile) return
            if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                activity.startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.packageName)), INSTALL_PERMISSION)
                return
            }
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".updates", file)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            activity.startActivityForResult(intent, INSTALL_APK)
        } catch (e: Exception) {
            AlertDialog.Builder(activity).setTitle(R.string.update_failed_title)
                .setMessage(e.message).setPositiveButton(android.R.string.ok, null).show()
        }
    }

    fun onActivityResult(activity: Activity, requestCode: Int): Boolean {
        if (requestCode == INSTALL_PERMISSION) {
            if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()) installApk(activity)
            return true
        }
        if (requestCode == INSTALL_APK) {
            try { reconcileInstalledApk(activity) } catch (e: Exception) { Log.w(TAG, "APK state", e) }
            return true
        }
        return false
    }
}
