package file

import android.content.Context
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Persistent diagnostics beside build.ini; private/external app storage is a fallback. */
object UpdateLog {
    private var lastPath: String? = null

    private fun candidates(ctx: Context): List<File> {
        val folders = mutableListOf<File>()
        try { BuildManifest.manifestFile(ctx)?.parentFile?.let { folders.add(it) } }
        catch (_: Exception) {}
        try { ctx.getExternalFilesDir(null)?.let { folders.add(it) } }
        catch (_: Exception) {}
        folders.add(ctx.filesDir)
        return folders.distinctBy { it.absolutePath }.map { File(it, "Update.log") }
    }

    @Synchronized
    fun path(ctx: Context): String = lastPath ?: candidates(ctx).last().absolutePath

    @Synchronized
    fun write(ctx: Context, phase: String, message: String = "", error: Throwable? = null) {
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        date.timeZone = TimeZone.getTimeZone("UTC")
        val line = "${date.format(Date())} pid=${android.os.Process.myPid()} [$phase] $message\n" +
            (error?.let { Log.getStackTraceString(it) + "\n" } ?: "")
        for (target in candidates(ctx)) {
            try {
                if (!target.parentFile!!.isDirectory) continue
                if (target.length() > 4 * 1024 * 1024) {
                    val old = File(target.parentFile, "Update.log.old")
                    if ((!old.exists() || old.delete()) && !target.renameTo(old)) continue
                }
                target.appendText(line, Charsets.UTF_8)
                lastPath = target.absolutePath
                return
            } catch (_: Exception) {}
        }
        Log.w("ArenaUpdater", "Cannot write Update.log: $line")
    }

    fun start(ctx: Context, action: String) {
        val game = PreferenceManager.getDefaultSharedPreferences(ctx).getString("game_files", "")
        write(ctx, "start", "action=$action package=${ctx.packageName} Android=${Build.VERSION.SDK_INT} game=$game")
        try {
            val file = BuildManifest.manifestFile(ctx)
            val m = BuildManifest.read(ctx)
            write(ctx, "manifest", "path=$file exists=${file?.isFile} version=${m?.contentVersion} build=${m?.engineBuild} " +
                "complete=${m?.complete} address=${m?.serverAddress}:${m?.serverPort} alternative=${m?.useAlternativeServer}")
        } catch (e: Exception) { write(ctx, "manifest_error", e.message.orEmpty(), e) }
    }
}
