/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.kde.kdeconnect.pingora

import android.content.ClipData
import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.database.MatrixCursor
import android.provider.DocumentsContract
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [23])
class ClipboardTransferTest {
    @Test
    fun `copied screenshot with generic provider type preserves PNG bytes`() {
        val uri = Uri.parse("content://clipboard.provider/copied-screenshot")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00)
        val resolver = mockk<ContentResolver> {
            every { getType(uri) } returns "application/octet-stream"
            every { openInputStream(uri) } returns ByteArrayInputStream(png)
        }

        val (mime, bytes) = ClipboardTransfer.readImage(resolver, uri, ClipboardTransfer.LEGACY_IMAGE_LIMIT)

        assertEquals("image/png", mime)
        assertArrayEquals(png, bytes)
    }
    @Test
    fun `copied image with a text fallback and generic MIME sends the image`() {
        val uri = Uri.parse("content://clipboard.provider/copied-image")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val descriptor = mockk<AssetFileDescriptor>(relaxed = true) {
            every { createInputStream() } returns mockk(relaxed = true) {
                every { read(any<ByteArray>()) } answers {
                    png.copyInto(firstArg())
                    png.size
                } andThen -1
            }
        }
        val resolver = mockk<ContentResolver> {
            every { openInputStream(uri) } returns ByteArrayInputStream("metadata".toByteArray())
            every { openTypedAssetFileDescriptor(uri, "image/*", null) } returns descriptor
        }
        val clip = ClipData("Image", arrayOf("application/octet-stream"), ClipData.Item("Preview", null, uri))

        val (mime, bytes) = ClipboardTransfer.readPayload(resolver, clip, ClipboardTransfer.LEGACY_IMAGE_LIMIT) as ClipboardPayload.Image

        assertEquals("image/png", mime)
        assertArrayEquals(png, bytes)
    }

    @Test
    fun `large screenshot uses the receiving laptop limit`() {
        val uri = Uri.parse("content://clipboard.provider/large-screenshot")
        val png = ByteArray(5 * 1024 * 1024)
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(png)
        val resolver = mockk<ContentResolver> {
            every { openInputStream(uri) } answers { ByteArrayInputStream(png) }
        }
        val clip = ClipData("Screenshot", arrayOf("image/png"), ClipData.Item(uri))

        val (_, bytes) = ClipboardTransfer.readPayload(resolver, clip, ClipboardTransfer.MAX_IMAGE) as ClipboardPayload.Image

        assertArrayEquals(png, bytes)
    }

    @Test
    @Config(sdk = [28])
    fun `send clipboard sends an OS screenshot saved after the previous copied image`() {
        val payload = screenshotPayload(copiedAt = 1000L, screenshotAt = 2000L)
        assertEquals("image/png", (payload as ClipboardPayload.Image).mime)
        assertArrayEquals(screenshotPng, payload.bytes)
    }

    @Test
    @Config(sdk = [28])
    fun `text copied after a screenshot wins`() {
        assertEquals(ClipboardPayload.Text("Synthetic image preview"), screenshotPayload(copiedAt = 2500L, screenshotAt = 2000L))
    }

    @Test
    @Config(sdk = [28])
    fun `an expired screenshot is not sent when Android has cleared the clipboard`() {
        assertEquals(ClipboardPayload.Text(""), screenshotPayload(copiedAt = null, screenshotAt = 2000L, now = 700000L))
    }

    @Test
    @Config(sdk = [28])
    fun `a new screenshot sends when Android has cleared the clipboard`() {
        assertArrayEquals(screenshotPng, (screenshotPayload(copiedAt = null, screenshotAt = 2000L) as ClipboardPayload.Image).bytes)
    }

    @Test
    @Config(sdk = [28])
    fun `a trashed screenshot never replaces the latest visible capture`() {
        assertArrayEquals(screenshotPng, (screenshotPayload(copiedAt = 1000L, screenshotAt = 2000L, trashedAt = 2500L) as ClipboardPayload.Image).bytes)
    }

    @Test
    fun `an unreadable image URI never sends its preview text as a successful clipboard`() {
        val uri = Uri.parse("content://clipboard.provider/unavailable")
        val resolver = mockk<ContentResolver> {
            every { openInputStream(uri) } throws java.io.FileNotFoundException()
            every { openTypedAssetFileDescriptor(uri, "image/*", null) } throws java.io.FileNotFoundException()
        }
        val clip = ClipData("Image", arrayOf("application/octet-stream"), ClipData.Item("Synthetic image preview", null, uri))
        org.junit.Assert.assertThrows(java.io.FileNotFoundException::class.java) {
            ClipboardTransfer.readPayload(resolver, clip, ClipboardTransfer.MAX_IMAGE)
        }
    }

    private val screenshotPng = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 42)

    private fun screenshotPayload(copiedAt: Long?, screenshotAt: Long, now: Long = 3000L, trashedAt: Long? = null): ClipboardPayload {
        val folder = Uri.parse("content://screenshots/tree/primary%3APictures%2FScreenshots")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, "primary:Pictures/Screenshots")
        val image = DocumentsContract.buildDocumentUriUsingTree(folder, "primary:Pictures/Screenshots/new.png")
        val trash = DocumentsContract.buildDocumentUriUsingTree(folder, "primary:Pictures/Screenshots/.trashed-old.png")
        val clip = copiedAt?.let {
            ClipData.newPlainText("Old clip", "Synthetic image preview").apply {
                ReflectionHelpers.callInstanceMethod<Void>(description, "setTimestamp", ClassParameter.from(Long::class.javaPrimitiveType, it))
            }
        }
        val resolver = mockk<ContentResolver> {
            every { query(children, any(), any(), any(), any()) } answers {
                MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_DISPLAY_NAME)).apply {
                    addRow(arrayOf<Any>("primary:Pictures/Screenshots/new.png", "image/png", screenshotAt, "new.png"))
                    if (trashedAt != null) addRow(arrayOf<Any>("primary:Pictures/Screenshots/.trashed-old.png", "image/png", trashedAt, ".trashed-old.png"))
                }
            }
            every { openInputStream(image) } returns ByteArrayInputStream(screenshotPng)
            every { openInputStream(trash) } returns ByteArrayInputStream(screenshotPng.copyOf().apply { this[lastIndex] = 43 })
        }

        return ClipboardTransfer.readPayload(resolver, clip, ClipboardTransfer.MAX_IMAGE, folder, now = now)
    }

}
