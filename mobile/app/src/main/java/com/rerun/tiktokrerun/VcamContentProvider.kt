package com.rerun.tiktokrerun

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Exposes the currently-staged MP4 to our vcam LSPosed module running inside
 * TikTok. The video file lives in TiktokRerun's app-private cache dir
 * (`/data/data/com.rerun.tiktokrerun/cache/vcam/active.mp4`) so no shared-
 * storage permission ceremony is required on either side — TikTok talks to
 * us through Binder via [openFile] and receives a read-only fd.
 *
 * URI: `content://com.rerun.tiktokrerun.vcam/video`.
 *
 * To stage a video, call [stage] from anywhere in the app (typically after
 * downloading or selecting it). The next time vcam reads the URI it gets
 * the new content.
 */
class VcamContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val file = activeFile(ctx)
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "openFile: no active video at ${file.absolutePath}")
            return null
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "video/mp4"

    /**
     * Returns one row with `_display_name` + `_size` so callers (including
     * `ContentResolver.query`) can introspect without opening the fd. Helpful
     * for vcam to detect "no video staged yet" before allocating decoder
     * resources.
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        val file = activeFile(ctx)
        if (!file.exists()) return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val row = cols.map { c ->
            when (c) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }.toTypedArray()
        cursor.addRow(row)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "VcamContentProvider"
        const val AUTHORITY = "com.rerun.tiktokrerun.vcam"
        val VIDEO_URI: Uri = Uri.parse("content://$AUTHORITY/video")

        fun activeFile(context: Context): File {
            val dir = File(context.cacheDir, "vcam").apply { mkdirs() }
            return File(dir, "active.mp4")
        }

        /**
         * Atomically replaces the active video with [source]'s bytes. Writes
         * to a temp file then renames so vcam never sees a half-written file.
         */
        fun stage(context: Context, source: File) {
            val target = activeFile(context)
            val tmp = File(target.parentFile, "active.mp4.tmp")
            FileInputStream(source).use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(target)) {
                // Fallback on filesystems where rename-over-existing fails.
                target.delete()
                if (!tmp.renameTo(target)) error("failed to stage ${source.absolutePath}")
            }
            Log.i(TAG, "staged ${source.absolutePath} (${target.length()} bytes)")
        }
    }
}
