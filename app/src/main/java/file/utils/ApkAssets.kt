package file.utils

import android.content.Context
import file.ApkAssetArchive
import java.io.File

object ApkAssets {
    fun sources(ctx: Context): List<File> {
        val info = ctx.applicationInfo
        return (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty().toList())
            .distinct().map { File(it) }
    }
    fun open(ctx: Context): ApkAssetArchive = ApkAssetArchive(sources(ctx))
}
