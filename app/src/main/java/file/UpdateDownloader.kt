/*
    UpdateDownloader - downloads an update .zip from a remote URL and extracts it
    into the user's selected game files directory.

    This is independent of OpenMW's own data pipeline: we just fetch a zip and
    unpack it on top of the chosen folder.
*/

package file

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.libopenmw.openmw.R
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object UpdateDownloader {

    private const val TAG = "UpdateDownloader"
    const val DEFAULT_UPDATE_URL = "https://mro1.myarena.site/Nirn/update.zip"
    const val PREF_UPDATE_URL = "pref_update_server_url"
    const val MORROWIND_SITE = "https://morrowind.site"

    /**
     * Entry point: validates that a game folder is selected, then starts the download/extract flow.
     *
     * @param activity host activity (for dialogs & toasts)
     * @param gameFilesPath the path currently saved in "game_files" pref (may be empty)
     * @param updateUrl URL to the .zip archive
     */
    fun startUpdate(activity: Activity, gameFilesPath: String, updateUrl: String) {
        if (gameFilesPath.isBlank()) {
            // user requested: english-only message telling to pick resources first
            Toast.makeText(
                activity,
                activity.getString(R.string.update_no_game_files),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val target = File(gameFilesPath)
        if (!target.exists() || !target.isDirectory) {
            Toast.makeText(
                activity,
                activity.getString(R.string.update_invalid_folder),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Confirm with the user before overwriting files
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_confirm_title)
            .setMessage(activity.getString(R.string.update_confirm_message, updateUrl, gameFilesPath))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runDownload(activity, target, updateUrl)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runDownload(activity: Activity, targetDir: File, updateUrl: String) {
        val dialog = ProgressDialog(activity).apply {
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setTitle(activity.getString(R.string.update_downloading_title))
            setMessage(activity.getString(R.string.update_downloading_message))
            setCancelable(false)
            max = 100
            progress = 0
            show()
        }

        val ui = Handler(Looper.getMainLooper())

        Thread {
            var tmpZip: File? = null
            try {
                // 1) download to cache
                tmpZip = File(activity.cacheDir, "update_${System.currentTimeMillis()}.zip")
                downloadTo(updateUrl, tmpZip) { bytesDone, totalBytes ->
                    ui.post {
                        if (totalBytes > 0) {
                            val pct = ((bytesDone * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            dialog.progress = pct
                            dialog.setMessage(
                                activity.getString(
                                    R.string.update_downloading_progress,
                                    bytesDone / 1024L,
                                    totalBytes / 1024L
                                )
                            )
                        } else {
                            dialog.setMessage(
                                activity.getString(
                                    R.string.update_downloading_indeterminate,
                                    bytesDone / 1024L
                                )
                            )
                        }
                    }
                }

                // 2) extract
                ui.post {
                    dialog.progress = 0
                    dialog.setTitle(activity.getString(R.string.update_extracting_title))
                    dialog.setMessage(activity.getString(R.string.update_extracting_message))
                }
                extractZip(tmpZip, targetDir) { doneEntries, totalEntries, currentName ->
                    ui.post {
                        if (totalEntries > 0) {
                            val pct = ((doneEntries * 100L) / totalEntries).toInt().coerceIn(0, 100)
                            dialog.progress = pct
                        }
                        dialog.setMessage(
                            activity.getString(R.string.update_extracting_progress, currentName)
                        )
                    }
                }

                ui.post {
                    try { dialog.dismiss() } catch (_: Exception) {}
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.update_done_title)
                        .setMessage(activity.getString(R.string.update_done_message, targetDir.absolutePath))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "update failed", e)
                val msg = e.message ?: e.javaClass.simpleName
                ui.post {
                    try { dialog.dismiss() } catch (_: Exception) {}
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.update_failed_title)
                        .setMessage(activity.getString(R.string.update_failed_message, msg))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } finally {
                try { tmpZip?.delete() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun downloadTo(url: String, out: File, onProgress: (Long, Long) -> Unit) {
        val u = URL(url)
        val conn = u.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.connect()

            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code")
            }

            val total = conn.contentLength.toLong() // may be -1

            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done: Long = 0
                    var lastReport: Long = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        // throttle UI updates to ~every 128 KB
                        if (done - lastReport >= 128 * 1024) {
                            onProgress(done, total)
                            lastReport = done
                        }
                    }
                    onProgress(done, total)
                }
            }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun extractZip(
        zipFile: File,
        targetDir: File,
        onProgress: (Int, Int, String) -> Unit
    ) {
        // count entries first for progress
        var total = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zi ->
            while (zi.nextEntry != null) {
                total++
                zi.closeEntry()
            }
        }

        var done = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zi ->
            var entry: ZipEntry? = zi.nextEntry
            val buf = ByteArray(64 * 1024)
            val targetCanonical = targetDir.canonicalPath

            while (entry != null) {
                val name = entry.name
                val outFile = File(targetDir, name)

                // Zip Slip protection
                val outCanonical = outFile.canonicalPath
                if (!outCanonical.startsWith(targetCanonical + File.separator) &&
                    outCanonical != targetCanonical) {
                    throw SecurityException("Zip entry outside target: $name")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        while (true) {
                            val n = zi.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }

                zi.closeEntry()
                done++
                onProgress(done, total, name)
                entry = zi.nextEntry
            }
        }
    }
}
