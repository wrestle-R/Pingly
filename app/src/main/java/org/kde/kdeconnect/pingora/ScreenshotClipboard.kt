/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract

/** Consulted only by an explicit Send clipboard action, never by a background watcher. */
internal object ScreenshotClipboard {
    private const val FOLDER = "clipboard_screenshot_folder"
    private const val RECENT = 10 * 60 * 1000L

    fun folder(context: Context): Uri? = widgetPreferences(context).getString(FOLDER, null)?.let(Uri::parse)

    fun save(context: Context, uri: Uri?) {
        widgetPreferences(context).edit().apply {
            if (uri == null) remove(FOLDER) else putString(FOLDER, uri.toString())
        }.apply()
    }

    fun latest(resolver: ContentResolver, folder: Uri, clip: ClipData?, now: Long): ClipData? {
        // Old Android versions cannot tell us whether text was copied after the capture.
        if (Build.VERSION.SDK_INT < 26 && clip != null) return null
        val copiedAt = if (Build.VERSION.SDK_INT >= 26) clip?.description?.timestamp ?: 0 else 0
        val after = maxOf(copiedAt, now - RECENT)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        var newest = after
        var result: ClipData? = null
        try {
            val cursor = resolver.query(children, columns, null, null, null) ?: throw ScreenshotFolderUnavailable()
            cursor.use {
                while (it.moveToNext()) {
                    val modified = it.getLong(2)
                    if (it.getString(3).orEmpty().startsWith(".")) continue
                    if (modified <= newest || modified > now + 2000 || !it.getString(1).orEmpty().startsWith("image/")) continue
                    newest = modified
                    val uri = DocumentsContract.buildDocumentUriUsingTree(folder, it.getString(0))
                    result = ClipData("Screenshot", arrayOf(it.getString(1)), ClipData.Item(uri))
                }
            }
        } catch (_: SecurityException) {
            throw ScreenshotFolderUnavailable()
        } catch (_: IllegalArgumentException) {
            throw ScreenshotFolderUnavailable()
        }
        return result
    }
}

internal class ScreenshotFolderUnavailable : IllegalStateException()
