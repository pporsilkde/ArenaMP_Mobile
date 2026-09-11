package file

import android.app.Activity
import ui.theme.ArenaGlass
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.libopenmw.openmw.R
import server.ServerRuntime
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Check and install are separate actions; this object never launches the game. */
@Suppress("DEPRECATION")
object LauncherUpdater {
    const val INSTALL_PERMISSION = 7401
    const val INSTALL_APK = 7402
    private const val TAG = "ArenaUpdater"
    @Volatile private var busy = false

    private fun state(activity: Activity) = activity.getSharedPreferences("arena_updater", 0)
    private fun apk(activity: Activity) = File(activity.filesDir, "updates/engine.apk")
    fun isBusy(): Boolean = busy

    private fun showFailure(activity: Activity, message: String) {
        UpdateLog.write(activity, "failure", message)
        if (activity.isFinishing || activity.isDestroyed) {
            state(activity).edit().putString("last_error", message).commit()
            return
        }
        ArenaGlass.Builder(activity)
            .setTitle(R.string.update_failed_title)
            .setMessage(message + "\n\n" + activity.getString(R.string.arena_update_log_hint, UpdateLog.path(activity)))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showPendingFailure(activity: Activity) {
        val pending = state(activity)
        val error = pending.getString("last_error", "").orEmpty()
        if (error.isNotBlank()) {
            pending.edit().remove("last_error").commit()
            showFailure(activity, error)
        }
    }

    /** Startup check only downloads check.ini. Missing network access leaves Play enabled. */
    fun checkAvailable(activity: Activity, onResult: (Boolean) -> Unit): Boolean {
        if (busy || activity.isFinishing || activity.isDestroyed) return false
        busy = true
        Thread {
            var available = false
            val checkFile = File(activity.cacheDir, "arena-available.ini")
            try {
                UpdateLog.start(activity, "check")
                BuildManifest.manifestFile(activity)?.let {
                    ContentUpdate.recover(File(it.parentFile, ".arena-android-update.properties"))
                }
                // After an APK replacement the new resources must be deployed
                // before build= is acknowledged, even when the network is down.
                AssetUpdater.ensureClientInstalled(activity)
                reconcileInstalledApk(activity)
                val current = BuildManifest.read(activity)
                if (current != null && current.checkUrl.isNotBlank()) {
                    val remote = readCheck(activity, current, checkFile, AtomicBoolean(false))
                    if (remote != null) {
                        available = ContentUpdate.revision(remote["version"]) > ContentUpdate.revision(current.contentVersion) ||
                            ContentUpdate.revision(remote["build"]) > ContentUpdate.revision(current.engineBuild)
                    }
                } else UpdateLog.write(activity, "no_check_url", "No build.ini/url_check; Play remains available")
                UpdateLog.write(activity, "check_finished", "button=${if (available) "Update" else "Play"}")
            } catch (e: Exception) {
                // Recovery/local installation failed. Only network/check.ini
                // failures may fall back to Play; an incomplete transaction
                // must be retried before the game reads partially replaced files.
                available = true
                UpdateLog.write(activity, "check_error", e.message.orEmpty(), e)
                activity.runOnUiThread { showFailure(activity, e.message ?: e.javaClass.simpleName) }
            } finally {
                checkFile.delete()
                activity.runOnUiThread {
                    busy = false
                    if (!activity.isFinishing && !activity.isDestroyed) onResult(available)
                }
            }
        }.start()
        return true
    }

    private fun readCheck(activity: Activity, current: BuildManifest.Data, checkFile: File,
                          cancel: AtomicBoolean): Map<String, String>? {
        UpdateLog.write(activity, "check_download", "url=${ContentUpdate.url(current.checkUrl)}")
        val remote = try {
            ContentUpdate.download(current.checkUrl, checkFile, true, "", cancel, ContentUpdate.Progress { _, _ -> })
            ContentUpdate.check(ContentUpdate.readText(checkFile))
        } catch (e: Exception) {
            UpdateLog.write(activity, if (cancel.get()) "cancelled" else "check_unavailable", e.message.orEmpty(), e)
            return null
        }
        UpdateLog.write(activity, "versions", "local version=${current.contentVersion} build=${current.engineBuild}; " +
            "remote version=${remote["version"]} build=${remote["build"]}")
        ContentUpdate.revision(current.contentVersion)
        ContentUpdate.revision(current.engineBuild)
        return remote
    }

    /** A downloaded APK is not an installed engine. Acknowledge only the observed package update. */
    fun reconcileInstalledApk(activity: Activity) {
        val pending = state(activity)
        val expected = pending.getLong("apk_version", -1)
        if (expected < 0) return
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val version = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        val beforeVersion = pending.getLong("before_version", -1)
        UpdateLog.write(activity, "apk_reconcile", "installed=$version expected=$expected before=$beforeVersion")
        // versionCode is the authoritative installation result. Some Android
        // filesystems report the same lastUpdateTime for two quick package
        // writes; relying on that timestamp alone made a successful update
        // look unfinished and caused the APK to be downloaded again.
        if (version < expected || (beforeVersion >= 0 && version <= beforeVersion)
            || (beforeVersion < 0 && installed.lastUpdateTime <= pending.getLong("before_update", Long.MAX_VALUE))) return
        // The system installer can hand control back to an existing process.
        // Drop fingerprints from the previous APK before testing/deploying the
        // new client/server assets.
        AssetUpdater.invalidateAll()
        try { ServerRuntime.prepareAfterPackageUpdate(activity) }
        catch (e: Throwable) { UpdateLog.write(activity, "server_post_apk_prepare_error", e.message ?: e.javaClass.simpleName, e) }
        val manifestFile = BuildManifest.manifestFile(activity) ?: return
        if (manifestFile.canonicalPath != pending.getString("manifest", "")) return
        if (!AssetUpdater.clientIsCurrent(activity)) {
            UpdateLog.write(activity, "apk_assets_pending", "Waiting for resource deployment before stamping build")
            return
        }
        val m = BuildManifest.read(activity) ?: return
        val revision = pending.getString("build", "").orEmpty()
        if (ContentUpdate.revision(revision) > ContentUpdate.revision(m.engineBuild)) {
            ContentUpdate.stampEngineBuild(manifestFile, revision)
        }
        pending.edit().clear().commit()
        apk(activity).delete()
        UpdateLog.write(activity, "apk_installed", "build=$revision; installed APK and deployed assets confirmed")
    }

    fun update(activity: Activity, onReady: () -> Unit) {
        if (busy || activity.isFinishing) return
        UpdateLog.start(activity, "update")
        val manifest = try { BuildManifest.read(activity) } catch (e: Exception) {
            showFailure(activity, e.message ?: e.javaClass.simpleName); return
        }
        if (manifest == null) { onReady(); return }
        val manifestFile = BuildManifest.manifestFile(activity) ?: run { onReady(); return }
        val data = File(GameInstaller.getDataFiles(activity)).canonicalFile
        val journal = File(manifestFile.parentFile, ".arena-android-update.properties")
        busy = true
        val cancel = AtomicBoolean(false)
        val dialog = ArenaGlass.Progress(activity).apply {
            setTitle(R.string.arena_update_title)
            setMessage(activity.getString(R.string.arena_update_check))
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = true
            setCancelable(true)
            setCanceledOnTouchOutside(false)
            setOnCancelListener { cancel.set(true); UpdateLog.write(activity, "cancelled", "User cancelled the update") }
            show()
        }
        fun ui(action: () -> Unit) { activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) action() } }
        var completion: (() -> Unit)? = null
        fun finish(action: () -> Unit) { completion = action }
        var lastProgressLog = 0L
        val progress = ContentUpdate.Progress { done, total ->
            ui { if (total > 0) { dialog.isIndeterminate = false; dialog.progress = (done * 100 / total).toInt() } }
            if (System.currentTimeMillis() - lastProgressLog > 5000) {
                lastProgressLog = System.currentTimeMillis()
                UpdateLog.write(activity, "download_progress", "bytes=$done total=$total")
            }
        }
        Thread {
            var stage: File? = null
            val checkFile = File(activity.cacheDir, "arena-check.ini")
            val zip = File(activity.cacheDir, "arena-content.zip")
            try {
                // Recovery happens even when check.ini is absent/unreachable.
                ContentUpdate.recover(journal)
                UpdateLog.write(activity, "recovery", "journal=$journal recovered")
                ui { dialog.setMessage(activity.getString(R.string.arena_update_assets)) }
                AssetUpdater.ensureClientInstalled(activity)
                ui { dialog.setMessage(activity.getString(R.string.arena_update_check)) }
                reconcileInstalledApk(activity)
                val current = BuildManifest.read(activity) ?: throw IllegalStateException("build.ini is missing")
                if (current.checkUrl.isBlank()) {
                    finish { onReady() }; return@Thread
                }
                val remote = readCheck(activity, current, checkFile, cancel)
                if (remote == null || cancel.get()) { finish { onReady() }; return@Thread }
                val contentNeeded = ContentUpdate.revision(remote["version"]) > ContentUpdate.revision(current.contentVersion)
                val engineNeeded = ContentUpdate.revision(remote["build"]) > ContentUpdate.revision(current.engineBuild)
                UpdateLog.write(activity, "comparison", "contentNeeded=$contentNeeded engineNeeded=$engineNeeded")
                // APK is fully downloaded and validated before applying content, so an invalid
                // engine download does not leave a partially updated content/engine combination.
                var downloadedApk: File? = null
                if (engineNeeded) {
                    if (current.androidUrl.isBlank())
                        throw IllegalStateException("build.ini has no url_android for this engine update")
                    ui { dialog.setMessage(activity.getString(R.string.arena_update_engine)); dialog.isIndeterminate = true }
                    val finalApk = apk(activity)
                    finalApk.parentFile?.mkdirs()
                    val pending = state(activity)
                    val reuse = finalApk.isFile && pending.getString("build", "") == remote["build"]
                        && pending.getString("manifest", "") == manifestFile.canonicalPath
                        && pending.getString("url", "") == current.androidUrl
                        && pending.getString("sha256", "") == remote["sha256_android"].orEmpty()
                    if (!reuse) {
                        val part = File(finalApk.parentFile, "engine.apk.part")
                        try {
                            UpdateLog.write(activity, "apk_download", "url=${ContentUpdate.url(current.androidUrl)} target=$part sha256=${remote["sha256_android"]}")
                            ContentUpdate.download(current.androidUrl, part, false, remote["sha256_android"].orEmpty(), cancel, progress)
                            UpdateLog.write(activity, "apk_download_complete", "bytes=${part.length()}")
                            validateApk(activity, part)
                            if (finalApk.exists() && !finalApk.delete()) throw IllegalStateException("Cannot replace cached APK")
                            if (!part.renameTo(finalApk)) throw IllegalStateException("Cannot save downloaded APK")
                        } finally { part.delete() }
                    } else UpdateLog.write(activity, "apk_cached", "Reusing validated $finalApk")
                    val info = validateApk(activity, finalApk)
                    val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
                    val apkVersion = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                    if (!pending.edit().putString("build", remote["build"])
                            .putString("manifest", manifestFile.canonicalPath)
                            .putString("url", current.androidUrl)
                            .putString("sha256", remote["sha256_android"].orEmpty())
                            .putLong("apk_version", apkVersion)
                            .putLong("before_version", if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong())
                            .putLong("before_update", installed.lastUpdateTime).commit())
                        throw IllegalStateException("Cannot save APK installation state")
                    downloadedApk = finalApk
                }
                if (contentNeeded) {
                    if (current.updateUrl.isBlank())
                        throw IllegalStateException("build.ini has no url_update for this content update")
                    ui { dialog.setMessage(activity.getString(R.string.arena_update_content)); dialog.isIndeterminate = true }
                    UpdateLog.write(activity, "content_download", "url=${ContentUpdate.url(current.updateUrl)} sha256=${remote["sha256_update"]}")
                    ContentUpdate.download(current.updateUrl, zip, false, remote["sha256_update"].orEmpty(), cancel, progress)
                    UpdateLog.write(activity, "content_download_complete", "bytes=${zip.length()}")
                    stage = File(data.parentFile, ".arena-android-stage-" + System.currentTimeMillis())
                    ui { dialog.setMessage(activity.getString(R.string.arena_update_extract)); dialog.isIndeterminate = true }
                    val payload = ContentUpdate.extract(zip, stage, cancel)
                    UpdateLog.write(activity, "content_extracted", "payload=$payload destination=$data")
                    if (cancel.get()) { finish { onReady() }; return@Thread }
                    // Cancellation is allowed during download/staging; committing must finish or roll back.
                    ui { dialog.setCancelable(false) }
                    ContentUpdate.install(payload, data, manifestFile, remote["version"]!!, journal)
                    UpdateLog.write(activity, "content_installed", "version=${remote["version"]}")
                }
                finish {
                    if (downloadedApk != null) installApk(activity)
                    else onReady()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update failed", e)
                UpdateLog.write(activity, "update_error", e.message.orEmpty(), e)
                if (activity.isFinishing || activity.isDestroyed)
                    state(activity).edit().putString("last_error", e.message ?: e.javaClass.simpleName).commit()
                finish {
                    if (!cancel.get()) showFailure(activity, e.message ?: e.javaClass.simpleName)
                    onReady()
                }
            } finally {
                checkFile.delete(); zip.delete()
                try { stage?.let { ContentUpdate.deleteTree(it) } } catch (_: Exception) {}
                // Release the worker only AFTER staging cleanup. A new check
                // or download must not share temp files with a finishing job.
                activity.runOnUiThread {
                    busy = false
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        dialog.dismiss()
                        completion?.invoke()
                    } else UpdateLog.write(activity, "activity_closed", "Update finished after the launcher activity was closed; reopen to continue")
                }
            }
        }.start()
    }

    private fun validateApk(activity: Activity, file: File): android.content.pm.PackageInfo {
        val pm = activity.packageManager
        val info = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
            ?: throw IllegalArgumentException("Downloaded file is not an APK")
        val installed = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
        UpdateLog.write(activity, "apk_validate", "path=$file package=${info.packageName} installed=${activity.packageName}")
        require(info.packageName == activity.packageName) { "APK package/applicationId does not match this launcher" }
        val signatures = info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        require(signatures.isNotEmpty() && signatures == installed.signatures?.map { it.toCharsString() }?.toSet()) {
            "APK must be signed with the same release key as the installed launcher"
        }
        val newVersion = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val oldVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        UpdateLog.write(activity, "apk_versions", "downloaded=$newVersion installed=$oldVersion")
        require(newVersion > oldVersion) { "APK versionCode must be higher than the installed version" }
        return info
    }

    private fun installApk(activity: Activity) {
        try {
            val file = apk(activity)
            check(file.isFile) { "Downloaded APK is missing: $file" }
            if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                UpdateLog.write(activity, "apk_permission", "Opening permission for package installation")
                activity.startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.packageName)), INSTALL_PERMISSION)
                return
            }
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".updates", file)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            UpdateLog.write(activity, "apk_installer_start", "apk=$file; waiting for system installation confirmation")
            activity.startActivityForResult(intent, INSTALL_APK)
        } catch (e: Exception) {
            UpdateLog.write(activity, "apk_installer_error", e.message.orEmpty(), e)
            showFailure(activity, e.message ?: e.javaClass.simpleName)
        }
    }

    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int): Boolean {
        if (requestCode == INSTALL_PERMISSION || requestCode == INSTALL_APK)
            UpdateLog.write(activity, "apk_activity_result", "request=$requestCode result=$resultCode")
        if (requestCode == INSTALL_PERMISSION) {
            if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()) {
                installApk(activity)
            } else {
                showFailure(activity, activity.getString(R.string.arena_update_install_permission))
            }
            return true
        }
        if (requestCode == INSTALL_APK) {
            try { reconcileInstalledApk(activity) } catch (e: Exception) { Log.w(TAG, "APK state", e) }
            // PackageInstaller returns here both for success and cancellation.
            // Keep the validated APK and tell the user what happened instead of
            // silently dismissing the update dialog while the old engine remains.
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            val installed = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            if (installed < state(activity).getLong("apk_version", -1))
                showFailure(activity, activity.getString(R.string.arena_update_install_cancelled))
            return true
        }
        return false
    }
}
