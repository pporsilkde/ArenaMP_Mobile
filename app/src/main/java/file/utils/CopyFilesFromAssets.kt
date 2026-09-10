/*
    Copyright (C) 2016 sandstranger
    Copyright (C) 2019 Ilya Zhuravlev

    This file is part of OpenMW-Android.

    OpenMW-Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    OpenMW-Android is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with OpenMW-Android.  If not, see <https://www.gnu.org/licenses/>.
*/

package file.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import android.content.Context

/**
 * Helper class to handle copying assets to the storage
 * @param context Android context to use
 */
class CopyFilesFromAssets(private val context: Context) {

    /**
     * Copies assets recursively
     * @param src Source directory in assets
     * @param dst Destination directory on disk, absolute path
     */
    @Throws(IOException::class)
    fun copy(src: String, dst: String) {
        val children = context.assets.list(src) ?: throw IOException("Cannot list APK assets: $src")
        val target = File(dst)
        if (children.isEmpty()) {
            if (target.parentFile?.isDirectory != true && target.parentFile?.mkdirs() != true)
                throw IOException("Cannot create asset directory: $dst")
            context.assets.open(src).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        } else {
            if (!target.isDirectory && !target.mkdirs()) throw IOException("Cannot create asset directory: $dst")
            children.forEach { copy("$src/$it", File(target, it).absolutePath) }
        }
    }
}
